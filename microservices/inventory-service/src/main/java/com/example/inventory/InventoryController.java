package com.example.inventory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.avro.generic.GenericRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/inventory")
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    private final InventoryItemRepository inventoryItemRepository;
    private final List<Map<String, Object>> orderDemandEvents = new CopyOnWriteArrayList<>();
    private final Set<String> consumerDedupe = ConcurrentHashMap.newKeySet();
    private final Counter reserveFailedCounter;

    public InventoryController(InventoryItemRepository inventoryItemRepository, MeterRegistry meterRegistry) {
        this.inventoryItemRepository = inventoryItemRepository;
        this.reserveFailedCounter = meterRegistry.counter("inventory.reserve.failed.total");
        log.info("Inventory initialized with {} SKU entries", inventoryItemRepository.count());
    }

    @KafkaListener(topics = {"orders.created"}, groupId = "inventory-service-order-events")
    public void onOrderCreated(GenericRecord payload) {
        log.info("KAFKA CONSUME START topic=orders.created group=inventory-service-order-events eventId={} orderId={}",
                payload.get("eventId"), payload.get("orderId"));
        String eventId = String.valueOf(payload.get("eventId"));
        if (!eventId.isBlank() && !consumerDedupe.add(eventId)) {
            log.info("Inventory dedupe skipped duplicate eventId={}", eventId);
            return;
        }

        Map<String, Object> event = new LinkedHashMap<>(KafkaEventSchemas.toMap(payload));
        event.put("receivedAt", Instant.now().toString());
        orderDemandEvents.add(event);
        log.info("KAFKA CONSUME COMPLETE topic=orders.created group=inventory-service-order-events eventId={} orderId={}",
                eventId, event.get("orderId"));
    }

    @GetMapping("/events/orders")
    public List<Map<String, Object>> consumedOrderEvents() {
        return new ArrayList<>(orderDemandEvents);
    }

    @GetMapping("/items")
    @Cacheable(cacheNames = "inventory-all")
    public Map<Integer, Integer> allStock() {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        inventoryItemRepository.findAll()
                .forEach(item -> result.put(item.getProductId(), item.getAvailable()));
        return result;
    }

    @GetMapping("/{productId}")
    @Cacheable(cacheNames = "inventory-item", key = "#productId")
    public Map<String, Object> byProduct(@PathVariable("productId") int productId) {
        InventoryItemEntity item = inventoryItemRepository.findById(productId).orElseGet(() -> {
            InventoryItemEntity created = new InventoryItemEntity();
            created.setProductId(productId);
            created.setAvailable(0);
            return inventoryItemRepository.save(created);
        });
        return Map.of("productId", productId, "available", item.getAvailable());
    }

    @PostMapping("/reserve")
    @CacheEvict(cacheNames = { "inventory-all", "inventory-item" }, allEntries = true)
    public Map<String, Object> reserve(@RequestBody ReserveRequest request) {
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be > 0");
        }

        InventoryItemEntity item = inventoryItemRepository.findById(request.productId()).orElseGet(() -> {
            InventoryItemEntity created = new InventoryItemEntity();
            created.setProductId(request.productId());
            created.setAvailable(0);
            return created;
        });
        if (item.getAvailable() < request.quantity()) {
            reserveFailedCounter.increment();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Insufficient stock for product " + request.productId());
        }
        item.setAvailable(item.getAvailable() - request.quantity());
        inventoryItemRepository.save(item);

        return Map.of("status", "RESERVED", "productId", request.productId(), "remaining", item.getAvailable());
    }

    @PostMapping("/release")
    @CacheEvict(cacheNames = { "inventory-all", "inventory-item" }, allEntries = true)
    public Map<String, Object> release(@RequestBody ReleaseRequest request) {
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be > 0");
        }
        InventoryItemEntity item = inventoryItemRepository.findById(request.productId()).orElseGet(() -> {
            InventoryItemEntity created = new InventoryItemEntity();
            created.setProductId(request.productId());
            created.setAvailable(0);
            return created;
        });
        item.setAvailable(item.getAvailable() + request.quantity());
        inventoryItemRepository.save(item);
        int updated = item.getAvailable();
        log.info("Inventory released productId={} quantity={} reason={} available={}",
                request.productId(), request.quantity(), request.reason(), updated);
        return Map.of("status", "RELEASED", "productId", request.productId(), "available", updated, "reason", request.reason());
    }

    @PostMapping("/admin/restock")
    @CacheEvict(cacheNames = { "inventory-all", "inventory-item" }, allEntries = true)
    public Map<String, Object> restock(@RequestBody RestockRequest request) {
        if (request.quantity() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity must be > 0");
        }

        InventoryItemEntity item = inventoryItemRepository.findById(request.productId()).orElseGet(() -> {
            InventoryItemEntity created = new InventoryItemEntity();
            created.setProductId(request.productId());
            created.setAvailable(0);
            return created;
        });
        item.setAvailable(item.getAvailable() + request.quantity());
        inventoryItemRepository.save(item);
        int updated = item.getAvailable();
        return Map.of("status", "RESTOCKED", "productId", request.productId(), "available", updated);
    }

    public record ReserveRequest(int productId, int quantity) {
    }

    public record ReleaseRequest(int productId, int quantity, String reason) {
    }

    public record RestockRequest(int productId, int quantity) {
    }
}
