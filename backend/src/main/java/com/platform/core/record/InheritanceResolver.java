package com.platform.core.record;

import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.TableDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Maps a logical record onto the physical tables of its joined-inheritance
 * chain. An "incident extends task" record is one logical record stored as
 * one row in task plus one row in incident, sharing a sys_id; this class
 * decides which value lands in which table.
 */
@Component
public class InheritanceResolver {

    private final SchemaCache cache;

    public InheritanceResolver(SchemaCache cache) {
        this.cache = cache;
    }

    /**
     * Split field values by the chain table that physically owns each column,
     * root first. Every chain table appears in the result even with no values
     * — an insert must create a row in every table of the chain or subsequent
     * chain JOINs would drop the record.
     */
    public Map<TableDefinition, Map<String, Object>> splitByOwner(TableDefinition table,
                                                                  Map<String, Object> values) {
        Map<TableDefinition, Map<String, Object>> split = new LinkedHashMap<>();
        for (TableDefinition t : cache.chain(table)) {
            split.put(t, new LinkedHashMap<>());
        }
        values.forEach((field, value) -> {
            TableDefinition owner = cache.owningTable(table, field).orElseThrow(
                    () -> new IllegalArgumentException("No owner for field " + field));
            split.get(owner).put(field, value);
        });
        return split;
    }
}
