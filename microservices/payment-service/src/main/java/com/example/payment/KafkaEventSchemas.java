package com.example.payment;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

public final class KafkaEventSchemas {

    private KafkaEventSchemas() {
    }

    private static final String PAYMENT_PROCESSED_SCHEMA_JSON = """
            {
              "type":"record",
              "name":"PaymentProcessedEvent",
              "namespace":"com.example.events",
              "fields":[
                {"name":"eventId","type":"string"},
                {"name":"eventType","type":"string"},
                {"name":"orderId","type":"string"},
                {"name":"userId","type":"string"},
                {"name":"amount","type":"double"},
                {"name":"status","type":"string"},
                {"name":"transactionId","type":"string"},
                {"name":"createdAt","type":"string"}
              ]
            }
            """;

    static final Schema PAYMENT_PROCESSED_SCHEMA = new Schema.Parser().parse(PAYMENT_PROCESSED_SCHEMA_JSON);

    static GenericRecord paymentProcessedEvent(String eventId,
                                               String orderId,
                                               String userId,
                                               double amount,
                                               String status,
                                               String transactionId,
                                               String createdAt) {
        GenericRecord record = new GenericData.Record(PAYMENT_PROCESSED_SCHEMA);
        record.put("eventId", eventId);
        record.put("eventType", "PAYMENT_PROCESSED");
        record.put("orderId", orderId);
        record.put("userId", userId);
        record.put("amount", amount);
        record.put("status", status);
        record.put("transactionId", transactionId);
        record.put("createdAt", createdAt);
        return record;
    }

    static Map<String, Object> toMap(GenericRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Schema.Field field : record.getSchema().getFields()) {
            result.put(field.name(), record.get(field.name()));
        }
        return result;
    }
}
