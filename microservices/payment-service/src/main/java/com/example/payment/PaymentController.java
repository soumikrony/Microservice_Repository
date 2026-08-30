package com.example.payment;

import java.io.Serializable;
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
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private static final Logger log = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentRecordRepository paymentRecordRepository;
    private final List<Map<String, Object>> orderEvents = new CopyOnWriteArrayList<>();
    private final KafkaTemplate<String, GenericRecord> kafkaTemplate;
    private final Set<String> consumerDedupe = ConcurrentHashMap.newKeySet();
    private final Map<String, Map<String, Object>> idempotentChargeResult = new ConcurrentHashMap<>();
    private final Counter approvedCounter;
    private final Counter declinedCounter;

    public PaymentController(PaymentRecordRepository paymentRecordRepository,
                             KafkaTemplate<String, GenericRecord> kafkaTemplate,
                             MeterRegistry meterRegistry) {
        this.paymentRecordRepository = paymentRecordRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.approvedCounter = meterRegistry.counter("payments.approved.total");
        this.declinedCounter = meterRegistry.counter("payments.declined.total");
    }

    @PostMapping("/charge")
    @CacheEvict(cacheNames = "payment-stats", allEntries = true)
    public Map<String, Object> charge(@RequestBody PaymentRequest request,
                                      @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        String effectiveKey = (idempotencyKey == null || idempotencyKey.isBlank())
                ? request.orderId()
                : idempotencyKey.trim();
        if (idempotentChargeResult.containsKey(effectiveKey)) {
            return idempotentChargeResult.get(effectiveKey);
        }

        if (request.amount() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "amount must be > 0");
        }

        boolean approved = !"FAIL".equalsIgnoreCase(request.paymentMethod());
        String transactionId = "TXN-" + System.currentTimeMillis();
        String createdAt = Instant.now().toString();
        PaymentRecordEntity record = new PaymentRecordEntity();
        record.setTransactionId(transactionId);
        record.setOrderId(request.orderId());
        record.setUserId(request.userId());
        record.setAmount(request.amount());
        record.setPaymentMethod(request.paymentMethod());
        record.setApproved(approved);
        record.setCreatedAt(createdAt);
        paymentRecordRepository.save(record);

        Map<String, Object> response = Map.of(
                "status", approved ? "APPROVED" : "DECLINED",
                "transactionId", transactionId,
                "orderId", request.orderId(),
                "amount", request.amount());
        idempotentChargeResult.put(effectiveKey, response);

        GenericRecord paymentEvent = KafkaEventSchemas.paymentProcessedEvent(
                transactionId,
                request.orderId(),
                request.userId(),
                request.amount(),
                approved ? "APPROVED" : "DECLINED",
                transactionId,
                createdAt);

        log.info("KAFKA PRODUCE START topic=payments.processed key={} eventType=PAYMENT_PROCESSED schema=Avro autoRegister=true",
                request.orderId());
        kafkaTemplate.send("payments.processed", request.orderId(), paymentEvent).whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("KAFKA PRODUCE FAILED topic=payments.processed key={} eventType=PAYMENT_PROCESSED",
                        request.orderId(), ex);
                return;
            }
            log.info("KAFKA PRODUCE SUCCESS topic=payments.processed key={} partition={} offset={}",
                    request.orderId(), result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
        });
        if (approved) {
            approvedCounter.increment();
        } else {
            declinedCounter.increment();
        }
        log.info("Payment processed orderId={} transactionId={} approved={}", request.orderId(), transactionId, approved);
        return response;
    }

    @KafkaListener(topics = {"orders.created"}, groupId = "payment-service-order-events")
    public void onOrderCreated(GenericRecord payload) {
        log.info("KAFKA CONSUME START topic=orders.created group=payment-service-order-events eventId={} orderId={}",
                payload.get("eventId"), payload.get("orderId"));
        String eventId = String.valueOf(payload.get("eventId"));
        if (!eventId.isBlank() && !consumerDedupe.add(eventId)) {
            log.info("Payment dedupe skipped duplicate eventId={}", eventId);
            return;
        }

        Map<String, Object> entry = new LinkedHashMap<>(KafkaEventSchemas.toMap(payload));
        entry.put("receivedAt", Instant.now().toString());
        orderEvents.add(entry);
        log.info("KAFKA CONSUME COMPLETE topic=orders.created group=payment-service-order-events eventId={} orderId={}",
                eventId, entry.get("orderId"));
    }

    @GetMapping("/events/orders")
    public List<Map<String, Object>> consumedOrderEvents() {
        return new ArrayList<>(orderEvents);
    }

    @GetMapping("/admin/stats")
    @Cacheable(cacheNames = "payment-stats")
    public Map<String, Object> stats() {
        List<PaymentRecordEntity> payments = paymentRecordRepository.findAll();
        long approvedCount = payments.stream().filter(PaymentRecordEntity::isApproved).count();
        double revenue = payments.stream().filter(PaymentRecordEntity::isApproved).mapToDouble(PaymentRecordEntity::getAmount).sum();
        return Map.of(
                "totalTransactions", payments.size(),
                "approvedTransactions", approvedCount,
                "revenue", revenue,
                "history", payments.stream().map(this::toRecord).toList(),
                "consumedOrderEvents", orderEvents.size());
    }

    public record PaymentRequest(String orderId, String userId, double amount, String paymentMethod) {
    }

    private PaymentRecord toRecord(PaymentRecordEntity entity) {
        return new PaymentRecord(
                entity.getOrderId(),
                entity.getUserId(),
                entity.getAmount(),
                entity.getPaymentMethod(),
                entity.isApproved(),
                entity.getTransactionId(),
                entity.getCreatedAt());
    }

    public record PaymentRecord(
            String orderId,
            String userId,
            double amount,
            String paymentMethod,
            boolean approved,
            String transactionId,
            String createdAt) implements Serializable {
        private static final long serialVersionUID = 1L;
    }
}
