package com.example.order;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

public final class KafkaEventSchemas {

    private KafkaEventSchemas() {
    }

    private static final String ORDER_CREATED_SCHEMA_JSON = """
            {
              "type":"record",
              "name":"OrderCreatedEvent",
              "namespace":"com.example.events",
              "fields":[
                {"name":"eventId","type":"string"},
                {"name":"eventType","type":"string"},
                {"name":"orderId","type":"string"},
                {"name":"userId","type":"string"},
                {"name":"itemCount","type":"int"},
                {"name":"total","type":"double"},
                {"name":"createdAt","type":"string"}
              ]
            }
            """;

    private static final String ORDER_FAILED_SCHEMA_JSON = """
            {
              "type":"record",
              "name":"OrderFailedEvent",
              "namespace":"com.example.events",
              "fields":[
                {"name":"eventId","type":"string"},
                {"name":"eventType","type":"string"},
                {"name":"orderId","type":"string"},
                {"name":"userId","type":"string"},
                {"name":"total","type":"double"},
                {"name":"reason","type":"string"},
                {"name":"createdAt","type":"string"}
              ]
            }
            """;

    static final Schema ORDER_CREATED_SCHEMA = new Schema.Parser().parse(ORDER_CREATED_SCHEMA_JSON);
    static final Schema ORDER_FAILED_SCHEMA = new Schema.Parser().parse(ORDER_FAILED_SCHEMA_JSON);

    static GenericRecord orderCreatedEvent(String eventId, String orderId, String userId, int itemCount, double total, String createdAt) {
        GenericRecord record = new GenericData.Record(ORDER_CREATED_SCHEMA);
        record.put("eventId", eventId);
        record.put("eventType", "ORDER_CREATED");
        record.put("orderId", orderId);
        record.put("userId", userId);
        record.put("itemCount", itemCount);
        record.put("total", total);
        record.put("createdAt", createdAt);
        return record;
    }

    static GenericRecord orderFailedEvent(String eventId, String orderId, String userId, double total, String reason, String createdAt) {
        GenericRecord record = new GenericData.Record(ORDER_FAILED_SCHEMA);
        record.put("eventId", eventId);
        record.put("eventType", "ORDER_FAILED");
        record.put("orderId", orderId);
        record.put("userId", userId);
        record.put("total", total);
        record.put("reason", reason);
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
