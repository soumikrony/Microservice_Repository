package com.example.inventory;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;

public final class KafkaEventSchemas {

    private KafkaEventSchemas() {
    }

    static Map<String, Object> toMap(GenericRecord record) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Schema.Field field : record.getSchema().getFields()) {
            result.put(field.name(), record.get(field.name()));
        }
        return result;
    }
}
