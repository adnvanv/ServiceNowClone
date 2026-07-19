package com.platform.core.record;

import com.platform.core.error.BadRequestException;
import com.platform.core.error.ConflictException;
import com.platform.core.error.NotFoundException;
import com.platform.core.metadata.BaseColumns;
import com.platform.core.metadata.ChoiceDefinition;
import com.platform.core.metadata.FieldDefinition;
import com.platform.core.metadata.FieldType;
import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.TableDefinition;
import com.platform.core.query.QueryNode;
import com.platform.core.query.RecordQuery;
import com.platform.core.query.SqlBuilder;
import com.platform.core.query.ValueCoercion;
import com.platform.core.security.AclDecision;
import com.platform.core.security.AclService;
import com.platform.core.security.UserContext;
import com.platform.core.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.platform.core.ddl.IdentifierValidator.quote;

/**
 * The single choke point for all record access. Every read goes through
 * {@link #fetch}/{@link #count}, every mutation through {@link #insert}/
 * {@link #update}/{@link #delete} — controllers never build SQL, and ACLs are
 * enforced here rather than in any HTTP layer, so no new endpoint can forget
 * them. This is also where Phase 2 (business rules) will hook before/after
 * every mutation.
 *
 * Reads inject the user's ACL read condition into the WHERE clause: rows the
 * user may not see are never fetched, and pagination/counts are computed on
 * the filtered set. Mutations evaluate ACLs against the affected record
 * before touching the database.
 */
@Service
public class RecordService implements RecordQuery.Executor {

    private final NamedParameterJdbcTemplate jdbc;
    private final SchemaCache cache;
    private final SqlBuilder sqlBuilder;
    private final AclService acl;
    private final InheritanceResolver inheritance;
    private final TenantContext tenant;
    private final TransactionTemplate tx;

    public RecordService(NamedParameterJdbcTemplate jdbc, SchemaCache cache, SqlBuilder sqlBuilder,
                         AclService acl, InheritanceResolver inheritance, TenantContext tenant,
                         PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.cache = cache;
        this.sqlBuilder = sqlBuilder;
        this.acl = acl;
        this.inheritance = inheritance;
        this.tenant = tenant;
        this.tx = new TransactionTemplate(txManager);
    }

    @PostConstruct
    void bindAsQueryExecutor() {
        RecordQuery.bindExecutor(this);
    }

    // ------------------------------------------------------------------
    // Reads
    // ------------------------------------------------------------------

    @Override
    public List<Record> fetch(RecordQuery query, UserContext user) {
        TableDefinition table = cache.require(query.tableName());
        AclDecision decision = acl.readDecision(table, user);
        if (decision instanceof AclDecision.Deny) {
            return List.of();
        }
        QueryNode where = withAclCondition(query.toNode(), decision);

        List<String> projection = query.projection() != null
                ? query.projection() : sqlBuilder.defaultProjection(table);
        List<String> displayFields = query.displayValues()
                ? referenceFieldsIn(table, projection) : List.of();

        SqlBuilder.CompiledQuery compiled = sqlBuilder.buildSelect(table, where, projection,
                displayFields, query.orderBys(), query.limitValue(), query.offsetValue());

        List<Record> records = jdbc.query(compiled.sql(), compiled.params(),
                (rs, i) -> mapRow(table, rs));
        for (Record record : records) {
            stripUnreadableFields(table, record, user);
            if (query.displayValues()) {
                addChoiceDisplayValues(table, record, projection);
            }
        }
        return records;
    }

    @Override
    public long count(RecordQuery query, UserContext user) {
        TableDefinition table = cache.require(query.tableName());
        AclDecision decision = acl.readDecision(table, user);
        if (decision instanceof AclDecision.Deny) {
            return 0;
        }
        QueryNode where = withAclCondition(query.toNode(), decision);
        SqlBuilder.CompiledQuery compiled = sqlBuilder.buildCount(table, where);
        Long count = jdbc.queryForObject(compiled.sql(), compiled.params(), Long.class);
        return count != null ? count : 0;
    }

    public Optional<Record> get(String tableName, UUID sysId, UserContext user, boolean displayValues) {
        return RecordQuery.of(tableName)
                .addQuery("sys_id", sysId)
                .withDisplayValues(displayValues)
                .limit(1)
                .fetch(user)
                .stream().findFirst();
    }

    // ------------------------------------------------------------------
    // Mutations
    // ------------------------------------------------------------------

    public Record insert(String tableName, Map<String, Object> payload, UserContext user) {
        TableDefinition table = cache.require(tableName);
        Map<String, Object> values = coercePayload(table, payload);
        applyDefaults(table, values);
        acl.checkOperation(table, "create", user, values);
        for (String field : payload.keySet()) {
            acl.checkField(table, "create", field, user, values);
        }
        validateMandatory(table, values, true);
        validateChoices(table, values);

        UUID sysId = UUID.randomUUID();
        Map<TableDefinition, Map<String, Object>> split = inheritance.splitByOwner(table, values);

        // One transaction across the whole chain: either the record exists in
        // every physical table or in none.
        tx.executeWithoutResult(status -> {
            boolean root = true;
            for (Map.Entry<TableDefinition, Map<String, Object>> e : split.entrySet()) {
                insertRow(e.getKey(), root, sysId, table.name(), e.getValue(), user);
                root = false;
            }
        });
        return reread(tableName, sysId, user);
    }

    public Record update(String tableName, UUID sysId, Map<String, Object> payload,
                         UserContext user, Integer expectedModCount) {
        TableDefinition table = cache.require(tableName);
        Record current = loadForMutation(table, sysId);

        acl.checkOperation(table, "write", user, current.asMap());
        for (String field : payload.keySet()) {
            acl.checkField(table, "write", field, user, current.asMap());
        }

        long currentModCount = ((Number) current.get(BaseColumns.SYS_MOD_COUNT)).longValue();
        if (expectedModCount != null && expectedModCount != currentModCount) {
            throw new ConflictException("Record was modified by someone else",
                    "Expected sys_mod_count " + expectedModCount + " but found " + currentModCount);
        }

        Map<String, Object> values = coercePayload(table, payload);
        validateMandatory(table, values, false);
        validateChoices(table, values);

        Map<TableDefinition, Map<String, Object>> split = inheritance.splitByOwner(table, values);
        TableDefinition root = cache.root(table);

        tx.executeWithoutResult(status -> {
            for (Map.Entry<TableDefinition, Map<String, Object>> e : split.entrySet()) {
                if (!e.getKey().sysId().equals(root.sysId()) && !e.getValue().isEmpty()) {
                    updateRow(e.getKey(), sysId, e.getValue());
                }
            }
            // The root row is always touched: audit columns and the
            // optimistic-lock counter live there. The sys_mod_count guard in
            // the WHERE clause catches a concurrent update that slipped in
            // between our read and this write.
            int updated = updateRootRow(root, sysId, split.get(root), user, currentModCount);
            if (updated == 0) {
                throw new ConflictException("Record was modified by someone else",
                        "Concurrent update detected on '" + tableName + "' " + sysId);
            }
        });
        return reread(tableName, sysId, user);
    }

    public void delete(String tableName, UUID sysId, UserContext user) {
        TableDefinition table = cache.require(tableName);
        Record current = loadForMutation(table, sysId);
        acl.checkOperation(table, "delete", user, current.asMap());

        // Deleting the root row cascades down the chain (child sys_id FKs are
        // ON DELETE CASCADE), removing the record from every physical table.
        TableDefinition root = cache.root(table);
        tx.executeWithoutResult(status -> jdbc.update(
                "delete from " + qualified(root.name()) + " where \"sys_id\" = :id",
                Map.of("id", sysId)));
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private QueryNode withAclCondition(QueryNode userQuery, AclDecision decision) {
        if (decision instanceof AclDecision.Restrict r) {
            return new QueryNode.And(List.of(userQuery, r.condition()));
        }
        return userQuery;
    }

    private List<String> referenceFieldsIn(TableDefinition table, List<String> projection) {
        List<String> refs = new ArrayList<>();
        for (String f : projection) {
            if (!f.contains(".") && !BaseColumns.isBase(f)) {
                cache.field(table, f)
                        .filter(fd -> fd.type() == FieldType.REFERENCE)
                        .ifPresent(fd -> refs.add(f));
            }
        }
        return refs;
    }

    private Record mapRow(TableDefinition table, java.sql.ResultSet rs) throws java.sql.SQLException {
        Record record = new Record(table.name());
        java.sql.ResultSetMetaData meta = rs.getMetaData();
        for (int i = 1; i <= meta.getColumnCount(); i++) {
            record.put(meta.getColumnLabel(i), normalize(rs.getObject(i)));
        }
        return record;
    }

    /** Normalise JDBC types to the platform's canonical value types. */
    private static Object normalize(Object value) {
        if (value instanceof Timestamp ts) {
            return ts.toInstant().atOffset(ZoneOffset.UTC);
        }
        return value;
    }

    private void stripUnreadableFields(TableDefinition table, Record record, UserContext user) {
        for (String field : List.copyOf(record.asMap().keySet())) {
            String baseField = field.endsWith("__display")
                    ? field.substring(0, field.length() - "__display".length()) : field;
            if (BaseColumns.isBase(baseField) || baseField.contains(".")) {
                continue;
            }
            if (acl.hasFieldReadRules(table, baseField)
                    && !acl.fieldReadable(table, baseField, user, record.asMap())) {
                record.remove(field);
            }
        }
    }

    private void addChoiceDisplayValues(TableDefinition table, Record record, List<String> projection) {
        for (String f : projection) {
            if (f.contains(".") || BaseColumns.isBase(f) || !record.has(f)) {
                continue;
            }
            Optional<FieldDefinition> fd = cache.field(table, f);
            if (fd.isPresent() && fd.get().type() == FieldType.CHOICE && record.get(f) != null) {
                cache.choices(table, f).stream()
                        .filter(c -> c.value().equals(record.get(f).toString()))
                        .findFirst()
                        .ifPresent(c -> record.put(f + "__display", c.label()));
            }
        }
    }

    /** Coerce a JSON payload, rejecting unknown fields and base columns. */
    private Map<String, Object> coercePayload(TableDefinition table, Map<String, Object> payload) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            String field = e.getKey();
            if (BaseColumns.isBase(field)) {
                throw new BadRequestException("Field '" + field + "' is platform-managed and read-only");
            }
            FieldDefinition fd = cache.field(table, field)
                    .orElseThrow(() -> new BadRequestException(
                            "Unknown field '" + field + "' on table '" + table.name() + "'"));
            values.put(field, ValueCoercion.coerceJson(fd.type(), e.getValue(), field));
        }
        return values;
    }

    private void applyDefaults(TableDefinition table, Map<String, Object> values) {
        for (FieldDefinition f : cache.fieldsInChain(table)) {
            if (f.defaultValue() != null && !values.containsKey(f.element())) {
                values.put(f.element(), ValueCoercion.coerce(f.type(), f.defaultValue(), f.element()));
            }
        }
    }

    /**
     * Mandatory is enforced here, not as NOT NULL in DDL (see DdlGenerator).
     * On insert every mandatory field must have a value; on update only
     * explicit attempts to clear one are rejected.
     */
    private void validateMandatory(TableDefinition table, Map<String, Object> values, boolean insert) {
        for (FieldDefinition f : cache.fieldsInChain(table)) {
            if (!f.mandatory()) {
                continue;
            }
            boolean present = values.containsKey(f.element());
            Object v = values.get(f.element());
            boolean empty = v == null || (v instanceof String s && s.isBlank());
            if ((insert || present) && empty) {
                throw new BadRequestException("Mandatory field '" + f.element() + "' is missing");
            }
        }
    }

    private void validateChoices(TableDefinition table, Map<String, Object> values) {
        for (Map.Entry<String, Object> e : values.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            Optional<FieldDefinition> fd = cache.field(table, e.getKey());
            if (fd.isEmpty() || fd.get().type() != FieldType.CHOICE) {
                continue;
            }
            List<ChoiceDefinition> choices = cache.choices(table, e.getKey());
            if (!choices.isEmpty() && choices.stream()
                    .noneMatch(c -> c.value().equals(e.getValue().toString()))) {
                throw new BadRequestException(
                        "Invalid choice value '" + e.getValue() + "' for field '" + e.getKey() + "'",
                        "Valid values: " + choices.stream().map(ChoiceDefinition::value).toList());
            }
        }
    }

    private void insertRow(TableDefinition table, boolean isRoot, UUID sysId, String className,
                           Map<String, Object> fields, UserContext user) {
        List<String> columns = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource();
        columns.add(BaseColumns.SYS_ID);
        params.addValue(BaseColumns.SYS_ID, sysId);
        if (isRoot) {
            columns.add(BaseColumns.SYS_CLASS_NAME);
            params.addValue(BaseColumns.SYS_CLASS_NAME, className);
            columns.add(BaseColumns.SYS_CREATED_BY);
            params.addValue(BaseColumns.SYS_CREATED_BY, user.username());
            columns.add(BaseColumns.SYS_UPDATED_BY);
            params.addValue(BaseColumns.SYS_UPDATED_BY, user.username());
        }
        int p = 0;
        List<String> placeholders = new ArrayList<>();
        for (String c : columns) {
            placeholders.add(":" + c);
        }
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String param = "f" + p++;
            columns.add(e.getKey());
            placeholders.add(":" + param);
            params.addValue(param, e.getValue());
        }
        String sql = "insert into " + qualified(table.name()) + " ("
                + String.join(", ", columns.stream().map(c -> quote(c)).toList())
                + ") values (" + String.join(", ", placeholders) + ")";
        jdbc.update(sql, params);
    }

    private void updateRow(TableDefinition table, UUID sysId, Map<String, Object> fields) {
        MapSqlParameterSource params = new MapSqlParameterSource().addValue("id", sysId);
        List<String> sets = new ArrayList<>();
        int p = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String param = "f" + p++;
            sets.add(quote(e.getKey()) + " = :" + param);
            params.addValue(param, e.getValue());
        }
        jdbc.update("update " + qualified(table.name()) + " set " + String.join(", ", sets)
                + " where \"sys_id\" = :id", params);
    }

    private int updateRootRow(TableDefinition root, UUID sysId, Map<String, Object> fields,
                              UserContext user, long expectedModCount) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", sysId)
                .addValue("actor", user.username())
                .addValue("modCount", expectedModCount);
        List<String> sets = new ArrayList<>();
        sets.add("\"sys_updated_on\" = now()");
        sets.add("\"sys_updated_by\" = :actor");
        sets.add("\"sys_mod_count\" = \"sys_mod_count\" + 1");
        int p = 0;
        for (Map.Entry<String, Object> e : fields.entrySet()) {
            String param = "r" + p++;
            sets.add(quote(e.getKey()) + " = :" + param);
            params.addValue(param, e.getValue());
        }
        return jdbc.update("update " + qualified(root.name()) + " set " + String.join(", ", sets)
                + " where \"sys_id\" = :id and \"sys_mod_count\" = :modCount", params);
    }

    /** Load a record for mutation checks: full projection, no ACL filtering, 404 if absent. */
    private Record loadForMutation(TableDefinition table, UUID sysId) {
        SqlBuilder.CompiledQuery compiled = sqlBuilder.buildSelect(table,
                new QueryNode.Condition("sys_id", com.platform.core.query.Operator.EQUALS,
                        sysId.toString()),
                null, List.of(), null, 1, null);
        return jdbc.query(compiled.sql(), compiled.params(), (rs, i) -> mapRow(table, rs))
                .stream().findFirst()
                .orElseThrow(() -> new NotFoundException(
                        "No record " + sysId + " on table '" + table.name() + "'"));
    }

    /**
     * Post-mutation re-read runs as system (the mutation already passed its
     * ACLs; a restrictive read ACL should not turn a successful write into an
     * error) but field-level read stripping is applied for the real user.
     */
    private Record reread(String tableName, UUID sysId, UserContext user) {
        TableDefinition table = cache.require(tableName);
        Record record = loadForMutation(table, sysId);
        stripUnreadableFields(table, record, user);
        return record;
    }

    private String qualified(String table) {
        return quote(tenant.schema()) + '.' + quote(table);
    }
}
