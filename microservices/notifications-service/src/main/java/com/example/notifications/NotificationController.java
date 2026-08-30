package com.example.notifications;

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
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final List<Map<String, Object>> events = new CopyOnWriteArrayList<>();
    private final Set<String> dedupe = ConcurrentHashMap.newKeySet();
    private final Counter consumedCounter;

    public NotificationController(MeterRegistry meterRegistry) {
        this.consumedCounter = meterRegistry.counter("notifications.events.consumed.total");
    }

    @KafkaListener(topics = {"orders.created", "orders.failed", "payments.processed"},
            groupId = "notifications-service-events")
    public void onEvent(GenericRecord payload) {
        log.info("KAFKA CONSUME START topics=orders.created,orders.failed,payments.processed group=notifications-service-events eventId={} eventType={} orderId={}",
                payload.get("eventId"), payload.get("eventType"), payload.get("orderId"));
        String eventId = String.valueOf(payload.get("eventId"));
        if (!eventId.isBlank() && !dedupe.add(eventId)) {
            log.info("Notification dedupe skipped duplicate eventId={}", eventId);
            return;
        }

        Map<String, Object> event = new LinkedHashMap<>(KafkaEventSchemas.toMap(payload));
        event.put("receivedAt", Instant.now().toString());
        events.add(event);
        consumedCounter.increment();
        log.info("KAFKA CONSUME COMPLETE group=notifications-service-events eventId={} eventType={} orderId={}",
                eventId, event.get("eventType"), event.get("orderId"));
    }

    @GetMapping("/admin/events")
    public List<Map<String, Object>> allEvents() {
        return new ArrayList<>(events);
    }

    @GetMapping("/admin/observability")
    public Map<String, Object> observabilitySnapshot() {
        long orderCreated = events.stream().filter(e -> "ORDER_CREATED".equals(String.valueOf(e.get("eventType")))).count();
        long orderFailed = events.stream().filter(e -> "ORDER_FAILED".equals(String.valueOf(e.get("eventType")))).count();
        long paymentProcessed = events.stream().filter(e -> "PAYMENT_PROCESSED".equals(String.valueOf(e.get("eventType")))).count();

        return Map.of(
                "eventsStored", events.size(),
                "orderCreatedEvents", orderCreated,
                "orderFailedEvents", orderFailed,
                "paymentProcessedEvents", paymentProcessed,
                "notificationLagEstimate", 0,
                "dlqCount", 0,
                "throughputHint", "Use prometheus metric notifications.events.consumed.total",
                "errorRateHint", "Use logs + alert rules on consumer exceptions");
    }
}
