package com.platform.api;

import com.platform.core.ddl.SchemaChangeRequests.ChoiceRequest;
import com.platform.core.ddl.SchemaChangeRequests.CreateTableRequest;
import com.platform.core.ddl.SchemaChangeRequests.FieldRequest;
import com.platform.core.ddl.SchemaMutationService;
import com.platform.core.metadata.BaseColumns;
import com.platform.core.metadata.ChoiceDefinition;
import com.platform.core.metadata.FieldDefinition;
import com.platform.core.metadata.FieldType;
import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.TableDefinition;
import com.platform.core.query.SqlBuilder;
import com.platform.core.security.AclService;
import com.platform.core.security.UserContext;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves the metadata the UI renders from and accepts schema mutations.
 * GET /meta/table/{table} is the contract that makes "no per-table frontend
 * components" possible: field types, choices, reference targets, inheritance
 * and the caller's per-field access all come from here.
 */
@RestController
@RequestMapping("/api/now/meta")
public class MetaApiController {

    private final SchemaCache cache;
    private final SchemaMutationService mutations;
    private final AclService acl;
    private final SqlBuilder sqlBuilder;

    public MetaApiController(SchemaCache cache, SchemaMutationService mutations, AclService acl,
                             SqlBuilder sqlBuilder) {
        this.cache = cache;
        this.mutations = mutations;
        this.acl = acl;
        this.sqlBuilder = sqlBuilder;
    }

    /** Tables the caller can read, for the navigator. */
    @GetMapping("/tables")
    public Map<String, Object> tables(UserContext user) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (TableDefinition t : cache.allTables()) {
            if (!acl.operationAllowed(t, "read", user)) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", t.name());
            entry.put("label", t.label());
            entry.put("extendable", t.extendable());
            entry.put("super_class", t.superClassId() != null
                    ? cache.byId(t.superClassId()).name() : null);
            result.add(entry);
        }
        return Map.of("result", result);
    }

    @GetMapping("/table/{table}")
    public Map<String, Object> describe(@PathVariable String table, UserContext user) {
        TableDefinition t = cache.require(table);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", t.name());
        result.put("label", t.label());
        result.put("extendable", t.extendable());
        result.put("super_class", t.superClassId() != null
                ? cache.byId(t.superClassId()).name() : null);
        result.put("chain", cache.chain(t).stream().map(TableDefinition::name).toList());
        result.put("display_field", sqlBuilder.displayField(t));
        result.put("base_columns", List.copyOf(BaseColumns.ROOT_DDL));

        Map<String, Object> access = new LinkedHashMap<>();
        access.put("can_read", acl.operationAllowed(t, "read", user));
        access.put("can_create", acl.operationAllowed(t, "create", user));
        access.put("can_write", acl.operationAllowed(t, "write", user));
        access.put("can_delete", acl.operationAllowed(t, "delete", user));
        result.put("access", access);

        List<Map<String, Object>> fields = new ArrayList<>();
        for (TableDefinition owner : cache.chain(t)) {
            for (FieldDefinition f : owner.fields()) {
                fields.add(fieldMeta(t, owner, f, user));
            }
        }
        result.put("fields", fields);
        return Map.of("result", result);
    }

    @PostMapping("/table")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createTable(@RequestBody CreateTableRequest request,
                                           UserContext user) {
        TableDefinition created = mutations.createTable(request, user);
        return describe(created.name(), user);
    }

    @PostMapping("/table/{table}/field")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addField(@PathVariable String table,
                                        @RequestBody FieldRequest request,
                                        UserContext user) {
        mutations.addField(table, request, user);
        return describe(table, user);
    }

    @PostMapping("/table/{table}/field/{element}/choice")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addChoice(@PathVariable String table,
                                         @PathVariable String element,
                                         @RequestBody ChoiceRequest request,
                                         UserContext user) {
        mutations.addChoice(table, element, request, user);
        return describe(table, user);
    }

    // ------------------------------------------------------------------

    private Map<String, Object> fieldMeta(TableDefinition table, TableDefinition owner,
                                          FieldDefinition f, UserContext user) {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("element", f.element());
        meta.put("label", f.label());
        meta.put("type", f.type().internalType());
        meta.put("max_length", f.maxLength());
        meta.put("mandatory", f.mandatory());
        meta.put("default_value", f.defaultValue());
        meta.put("display", f.display());
        meta.put("declared_on", owner.name());

        if (f.type() == FieldType.REFERENCE && f.referenceTableId() != null) {
            TableDefinition target = cache.byId(f.referenceTableId());
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("table", target.name());
            ref.put("label", target.label());
            ref.put("display_field", sqlBuilder.displayField(target));
            meta.put("reference", ref);
        }
        if (f.type() == FieldType.CHOICE) {
            List<Map<String, Object>> choices = new ArrayList<>();
            for (ChoiceDefinition c : cache.choices(table, f.element())) {
                Map<String, Object> choice = new LinkedHashMap<>();
                choice.put("value", c.value());
                choice.put("label", c.label());
                choice.put("sequence", c.sequence());
                choices.add(choice);
            }
            meta.put("choices", choices);
        }

        // Record-independent hints: fields the UI should hide or lock. The
        // record layer re-checks with the actual record on every access.
        meta.put("can_read", acl.fieldReadable(table, f.element(), user, null));
        meta.put("can_write", acl.fieldWritable(table, f.element(), user, null));
        return meta;
    }
}
