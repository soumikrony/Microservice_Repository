package com.example.order;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.avro.generic.GenericRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderWorkflowClient workflowClient;
    private final OrderRecordRepository orderRecordRepository;
    private final KafkaTemplate<String, GenericRecord> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final List<Map<String, Object>> orderStore = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> paymentEvents = new CopyOnWriteArrayList<>();
    private final Map<String, Map<String, Object>> idempotentResponses = new ConcurrentHashMap<>();
    private final Set<String> consumerDedupe = ConcurrentHashMap.newKeySet();

    private final Counter checkoutCounter;
    private final Counter checkoutFailedCounter;
    private final Counter compensationCounter;

    public OrderController(OrderWorkflowClient workflowClient,
                           OrderRecordRepository orderRecordRepository,
                           KafkaTemplate<String, GenericRecord> kafkaTemplate,
                           ObjectMapper objectMapper,
                           MeterRegistry meterRegistry) {
        this.workflowClient = workflowClient;
        this.orderRecordRepository = orderRecordRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.checkoutCounter = meterRegistry.counter("orders.checkout.total");
        this.checkoutFailedCounter = meterRegistry.counter("orders.checkout.failed.total");
        this.compensationCounter = meterRegistry.counter("orders.compensation.total");
    }

    @PostMapping("/checkout")
    @CacheEvict(cacheNames = { "orders-by-user", "orders-all" }, allEntries = true)
    public Map<String, Object> checkout(@RequestBody CheckoutRequest request,
                                        @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        return processCheckout(request, idempotencyKey);
    }

    private Map<String, Object> processCheckout(CheckoutRequest request, String idempotencyKey) {
        log.info("Checkout started userId={} paymentMethod={} idempotencyKey={}",
                request.userId(), request.paymentMethod(), idempotencyKey);

        if (request.userId() == null || request.userId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId is required");
        }

        String effectiveKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? "auto-" + request.userId() + "-" + System.currentTimeMillis()
                : idempotencyKey.trim();
        if (idempotentResponses.containsKey(effectiveKey)) {
            log.info("Idempotent replay detected key={} userId={}", effectiveKey, request.userId());
            return idempotentResponses.get(effectiveKey);
        }

        Map<String, Object> cart = workflowClient.getCart(request.userId());
        if (cart == null || !cart.containsKey("items")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart not found");
        }

        List<Map<String, Object>> items = (List<Map<String, Object>>) cart.get("items");
        if (items == null || items.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cart is empty");
        }

        double total = ((Number) cart.getOrDefault("total", 0.0)).doubleValue();
        String orderId = "ORD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        List<Map<String, Object>> reserved = new ArrayList<>();

        try {
            for (Map<String, Object> item : items) {
                int productId = ((Number) item.get("productId")).intValue();
                int quantity = ((Number) item.get("quantity")).intValue();
                Map<String, Object> reserveResponse = workflowClient.reserveItem(productId, quantity);
                reserved.add(Map.of("productId", productId, "quantity", quantity, "reserveResponse", reserveResponse));
            }

            Map<String, Object> paymentResponse = workflowClient.chargePayment(
                    orderId,
                    request.userId(),
                    total,
                    request.paymentMethod() == null ? "CARD" : request.paymentMethod());

            if (paymentResponse == null || !"APPROVED".equals(String.valueOf(paymentResponse.get("status")))) {
                compensateInventory(orderId, reserved, "PAYMENT_DECLINED");
                publishOrderFailed(orderId, request.userId(), total, "PAYMENT_DECLINED");
                checkoutFailedCounter.increment();
                throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED, "Payment declined");
            }

            workflowClient.clearCart(request.userId());
            Map<String, Object> order = buildOrder(orderId, request.userId(), items, total, paymentResponse, reserved);
            persistOrder(orderId, request.userId(), total, "CONFIRMED", order);

            orderStore.add(order);
            idempotentResponses.put(effectiveKey, order);
            publishOrderCreated(orderId, request.userId(), items.size(), total);
            checkoutCounter.increment();
            log.info("Checkout completed orderId={} userId={} status=CONFIRMED", orderId, request.userId());
            return order;
        } catch (ResponseStatusException ex) {
            compensateInventory(orderId, reserved, "WORKFLOW_EXCEPTION_" + ex.getStatusCode().value());
            publishOrderFailed(orderId, request.userId(), total, "WORKFLOW_EXCEPTION");
            checkoutFailedCounter.increment();
            throw ex;
        } catch (Exception ex) {
            compensateInventory(orderId, reserved, "UNEXPECTED_ERROR");
            publishOrderFailed(orderId, request.userId(), total, "UNEXPECTED_ERROR");
            checkoutFailedCounter.increment();
            log.error("Checkout failed orderId={}", orderId, ex);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Checkout failed: " + ex.getMessage());
        }
    }

    private Map<String, Object> buildOrder(String orderId, String userId, List<Map<String, Object>> items, double total,
                                           Map<String, Object> paymentResponse, List<Map<String, Object>> reserved) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("orderId", orderId);
        order.put("userId", userId);
        order.put("items", items);
        order.put("total", total);
        order.put("payment", paymentResponse);
        order.put("reserved", reserved);
        order.put("createdAt", Instant.now().toString());
        order.put("status", "CONFIRMED");
        return order;
    }

    private void compensateInventory(String orderId, List<Map<String, Object>> reserved, String reason) {
        if (reserved == null || reserved.isEmpty()) {
            return;
        }
        for (Map<String, Object> reservation : reserved) {
            int productId = ((Number) reservation.get("productId")).intValue();
            int quantity = ((Number) reservation.get("quantity")).intValue();
            workflowClient.releaseItem(productId, quantity, reason);
        }
        compensationCounter.increment();
        log.warn("Inventory compensation executed for orderId={} reason={} itemCount={}", orderId, reason, reserved.size());
    }

    private void publishOrderCreated(String orderId, String userId, int itemCount, double total) {
        GenericRecord event = KafkaEventSchemas.orderCreatedEvent(
                UUID.randomUUID().toString(),
                orderId,
                userId,
                itemCount,
                total,
                Instant.now().toString());
        log.info("KAFKA PRODUCE START topic=orders.created key={} eventType=ORDER_CREATED schema=Avro autoRegister=true",
                orderId);
        kafkaTemplate.send("orders.created", orderId, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("KAFKA PRODUCE FAILED topic=orders.created key={} eventType=ORDER_CREATED", orderId, ex);
                return;
            }
            log.info("KAFKA PRODUCE SUCCESS topic=orders.created key={} partition={} offset={}",
                    orderId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        });
    }

    private void publishOrderFailed(String orderId, String userId, double total, String reason) {
        GenericRecord event = KafkaEventSchemas.orderFailedEvent(
                UUID.randomUUID().toString(),
                orderId,
                userId,
                total,
                reason,
                Instant.now().toString());
        log.info("KAFKA PRODUCE START topic=orders.failed key={} eventType=ORDER_FAILED schema=Avro autoRegister=true",
                orderId);
        kafkaTemplate.send("orders.failed", orderId, event).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("KAFKA PRODUCE FAILED topic=orders.failed key={} eventType=ORDER_FAILED", orderId, ex);
                return;
            }
            log.info("KAFKA PRODUCE SUCCESS topic=orders.failed key={} partition={} offset={}",
                    orderId, result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        });
    }

    @KafkaListener(topics = "payments.processed", groupId = "order-service-payment-events")
    public void onPaymentProcessed(GenericRecord payload) {
        log.info("KAFKA CONSUME START topic=payments.processed group=order-service-payment-events eventId={} orderId={}",
                payload.get("eventId"), payload.get("orderId"));
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("receivedAt", Instant.now().toString());
        event.put("payload", KafkaEventSchemas.toMap(payload));

        String dedupeKey = String.valueOf(payload.get("eventId"));
        if (!consumerDedupe.add(dedupeKey)) {
            log.info("Order payment listener dedupe skipped payloadHash={}", dedupeKey);
            return;
        }

        paymentEvents.add(event);
        log.info("KAFKA CONSUME COMPLETE topic=payments.processed group=order-service-payment-events eventId={} orderId={}",
                dedupeKey, payload.get("orderId"));
    }

    @GetMapping("/events/payments")
    public List<Map<String, Object>> paymentEvents() {
        return paymentEvents;
    }

    @GetMapping("/user/{userId}")
    @Cacheable(cacheNames = "orders-by-user", key = "#userId")
    public List<Map<String, Object>> userOrders(@PathVariable("userId") String userId) {
        return orderRecordRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toMap)
                .toList();
    }

    @GetMapping("/admin/all")
    @Cacheable(cacheNames = "orders-all")
    public List<Map<String, Object>> allOrders() {
        return orderRecordRepository.findAll()
                .stream()
                .map(this::toMap)
                .toList();
    }

    private void persistOrder(String orderId, String userId, double total, String status, Map<String, Object> payload) {
        try {
            OrderRecordEntity entity = new OrderRecordEntity();
            entity.setOrderId(orderId);
            entity.setUserId(userId);
            entity.setTotal(total);
            entity.setStatus(status);
            entity.setCreatedAt(Instant.now().toString());
            entity.setPayload(objectMapper.writeValueAsString(payload));
            orderRecordRepository.save(entity);
        } catch (Exception ex) {
            log.warn("Failed to persist orderId={} in PostgreSQL", orderId, ex);
        }
    }

    private Map<String, Object> toMap(OrderRecordEntity entity) {
        try {
            return objectMapper.readValue(entity.getPayload(), new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return Map.of(
                    "orderId", entity.getOrderId(),
                    "userId", entity.getUserId(),
                    "total", entity.getTotal(),
                    "status", entity.getStatus(),
                    "createdAt", entity.getCreatedAt());
        }
    }

    public record CheckoutRequest(String userId, String paymentMethod) {
    }
}
