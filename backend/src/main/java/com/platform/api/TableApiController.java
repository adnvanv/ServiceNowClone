package com.platform.api;

import com.platform.core.error.NotFoundException;
import com.platform.core.query.RecordQuery;
import com.platform.core.record.Record;
import com.platform.core.record.RecordService;
import com.platform.core.security.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The generic Table API, mirroring ServiceNow's shape. One controller serves
 * every table that exists or will ever exist — it contains no per-table
 * logic and never builds SQL; all access, validation and ACL work happens in
 * RecordService.
 */
@RestController
@RequestMapping("/api/now/table")
public class TableApiController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 1000;

    private final RecordService records;

    public TableApiController(RecordService records) {
        this.records = records;
    }

    @GetMapping("/{table}")
    public ResponseEntity<Map<String, Object>> list(
            @PathVariable String table,
            @RequestParam(name = "sysparm_query", required = false) String query,
            @RequestParam(name = "sysparm_fields", required = false) String fields,
            @RequestParam(name = "sysparm_limit", required = false) Integer limit,
            @RequestParam(name = "sysparm_offset", required = false) Integer offset,
            @RequestParam(name = "sysparm_order_by", required = false) String orderBy,
            @RequestParam(name = "sysparm_display_value", required = false, defaultValue = "false")
            boolean displayValue,
            UserContext user) {

        RecordQuery recordQuery = RecordQuery.of(table)
                .addEncodedQuery(query)
                .withDisplayValues(displayValue)
                .limit(clamp(limit))
                .offset(offset != null ? Math.max(offset, 0) : 0);
        if (fields != null && !fields.isBlank()) {
            recordQuery.fields(splitCsv(fields));
        }
        applyOrderBy(recordQuery, orderBy);

        // Count runs on the same filtered query (ACL conditions included) so
        // pagination reflects what the user can actually see.
        long total = RecordQuery.of(table).addEncodedQuery(query).count(user);
        List<Map<String, Object>> result = recordQuery.fetch(user).stream()
                .map(r -> shape(r, displayValue))
                .toList();

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(total))
                .body(Map.of("result", result));
    }

    @GetMapping("/{table}/{sysId}")
    public Map<String, Object> get(
            @PathVariable String table,
            @PathVariable UUID sysId,
            @RequestParam(name = "sysparm_display_value", required = false, defaultValue = "false")
            boolean displayValue,
            UserContext user) {
        Record record = records.get(table, sysId, user, displayValue)
                // A record the user may not read is indistinguishable from a
                // missing one — 404, not 403, to avoid confirming existence.
                .orElseThrow(() -> new NotFoundException(
                        "No record " + sysId + " on table '" + table + "'"));
        return Map.of("result", shape(record, displayValue));
    }

    @PostMapping("/{table}")
    @org.springframework.web.bind.annotation.ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@PathVariable String table,
                                      @RequestBody Map<String, Object> payload,
                                      UserContext user) {
        Record created = records.insert(table, payload, user);
        return Map.of("result", shape(created, false));
    }

    @PatchMapping("/{table}/{sysId}")
    public Map<String, Object> update(@PathVariable String table,
                                      @PathVariable UUID sysId,
                                      @RequestBody Map<String, Object> payload,
                                      UserContext user) {
        // Optimistic locking: callers may send the sys_mod_count they loaded;
        // a mismatch with the current value is a 409.
        Integer expectedModCount = null;
        Object raw = payload.remove("sys_mod_count");
        if (raw instanceof Number n) {
            expectedModCount = n.intValue();
        } else if (raw instanceof String s && !s.isBlank()) {
            expectedModCount = Integer.parseInt(s);
        }
        Record updated = records.update(table, sysId, payload, user, expectedModCount);
        return Map.of("result", shape(updated, false));
    }

    @DeleteMapping("/{table}/{sysId}")
    public ResponseEntity<Void> delete(@PathVariable String table,
                                       @PathVariable UUID sysId,
                                       UserContext user) {
        records.delete(table, sysId, user);
        return ResponseEntity.noContent().build();
    }

    // ------------------------------------------------------------------

    private static int clamp(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    private static List<String> splitCsv(String csv) {
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    /** "field1,-field2": '-' prefix sorts descending. */
    private static void applyOrderBy(RecordQuery query, String orderBy) {
        if (orderBy == null || orderBy.isBlank()) {
            return;
        }
        for (String part : splitCsv(orderBy)) {
            if (part.startsWith("-")) {
                query.orderByDesc(part.substring(1));
            } else {
                query.orderBy(part);
            }
        }
    }

    /**
     * With display values requested, fields that resolved one are emitted as
     * { "value": raw, "display_value": resolved } (ServiceNow's
     * sysparm_display_value=all shape — the UI needs both the id and the
     * label); everything else stays a plain value.
     */
    private static Map<String, Object> shape(Record record, boolean displayValue) {
        Map<String, Object> map = record.asMap();
        if (!displayValue) {
            return map;
        }
        Map<String, Object> shaped = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String key = e.getKey();
            if (key.endsWith("__display")) {
                continue;
            }
            if (map.containsKey(key + "__display")) {
                Map<String, Object> both = new LinkedHashMap<>();
                both.put("value", e.getValue());
                both.put("display_value", map.get(key + "__display"));
                shaped.put(key, both);
            } else {
                shaped.put(key, e.getValue());
            }
        }
        return shaped;
    }
}
