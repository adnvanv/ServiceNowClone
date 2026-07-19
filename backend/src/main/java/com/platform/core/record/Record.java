package com.platform.core.record;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * A single row of a user table: the table it came from plus an ordered map of
 * field values. Values are plain Java objects (String, Long, Boolean,
 * OffsetDateTime, UUID, ...) as coerced by the query layer.
 */
public class Record {

    private final String tableName;
    private final Map<String, Object> values = new LinkedHashMap<>();

    public Record(String tableName) {
        this.tableName = tableName;
    }

    public Record(String tableName, Map<String, Object> initial) {
        this(tableName);
        values.putAll(initial);
    }

    public String tableName() {
        return tableName;
    }

    public Object get(String field) {
        return values.get(field);
    }

    public UUID sysId() {
        Object id = values.get("sys_id");
        return id instanceof UUID uuid ? uuid : id != null ? UUID.fromString(id.toString()) : null;
    }

    public void put(String field, Object value) {
        values.put(field, value);
    }

    public boolean has(String field) {
        return values.containsKey(field);
    }

    public void remove(String field) {
        values.remove(field);
    }

    /** Live view — mutations affect the record. */
    public Map<String, Object> asMap() {
        return values;
    }
}
