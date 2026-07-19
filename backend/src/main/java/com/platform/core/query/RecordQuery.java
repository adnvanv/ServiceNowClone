package com.platform.core.query;

import com.platform.core.record.Record;
import com.platform.core.security.UserContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Fluent, GlideRecord-flavoured query spec:
 *
 *   List<Record> open = RecordQuery.of("incident")
 *       .addQuery("active", true)
 *       .addQuery("priority", Operator.LTE, 2)
 *       .orderByDesc("sys_created_on")
 *       .limit(20)
 *       .fetch(userContext);
 *
 * The builder itself is passive — it accumulates an AST and options. fetch()
 * and count() take a UserContext because execution without an identity is not
 * a thing this platform offers: ACL enforcement happens inside the executor
 * (RecordService), below every possible caller.
 */
public class RecordQuery {

    /** Implemented by RecordService; the seam also makes RecordQuery testable. */
    public interface Executor {
        List<Record> fetch(RecordQuery query, UserContext user);

        long count(RecordQuery query, UserContext user);
    }

    // Bound once at startup by RecordService. Static so the documented
    // RecordQuery.of(...).fetch(user) shape works from anywhere; the tradeoff
    // (a global) is confined to this sugar — everything real lives in the
    // executor.
    private static volatile Executor executor;

    public static void bindExecutor(Executor e) {
        executor = e;
    }

    public static RecordQuery of(String tableName) {
        return new RecordQuery(tableName);
    }

    private final String tableName;
    private final List<QueryNode> conditions = new ArrayList<>();
    private final List<SqlBuilder.OrderBy> orderBys = new ArrayList<>();
    private List<String> fields;
    private Integer limit;
    private Integer offset;

    private RecordQuery(String tableName) {
        this.tableName = tableName;
    }

    public RecordQuery addQuery(String field, Object value) {
        return addQuery(field, Operator.EQUALS, value);
    }

    public RecordQuery addQuery(String field, Operator op, Object value) {
        conditions.add(new QueryNode.Condition(field, op, value != null ? value.toString() : null));
        return this;
    }

    /** AND a full encoded query string into this query. */
    public RecordQuery addEncodedQuery(String encoded) {
        QueryNode node = EncodedQueryParser.parse(encoded);
        if (!(node instanceof QueryNode.MatchAll)) {
            conditions.add(node);
        }
        return this;
    }

    public RecordQuery fields(List<String> fields) {
        this.fields = fields;
        return this;
    }

    public RecordQuery orderBy(String field) {
        orderBys.add(new SqlBuilder.OrderBy(field, false));
        return this;
    }

    public RecordQuery orderByDesc(String field) {
        orderBys.add(new SqlBuilder.OrderBy(field, true));
        return this;
    }

    public RecordQuery limit(int limit) {
        this.limit = limit;
        return this;
    }

    public RecordQuery offset(int offset) {
        this.offset = offset;
        return this;
    }

    public List<Record> fetch(UserContext user) {
        return requireExecutor().fetch(this, user);
    }

    public Optional<Record> fetchOne(UserContext user) {
        return fetch(user).stream().findFirst();
    }

    public long count(UserContext user) {
        return requireExecutor().count(this, user);
    }

    private static Executor requireExecutor() {
        Executor e = executor;
        if (e == null) {
            throw new IllegalStateException("RecordQuery executor not bound (application not started?)");
        }
        return e;
    }

    // --- accessors for the executor ---

    public String tableName() {
        return tableName;
    }

    /** The accumulated conditions as one AST. */
    public QueryNode toNode() {
        if (conditions.isEmpty()) {
            return new QueryNode.MatchAll();
        }
        return conditions.size() == 1 ? conditions.get(0) : new QueryNode.And(List.copyOf(conditions));
    }

    public List<SqlBuilder.OrderBy> orderBys() {
        return orderBys;
    }

    public List<String> projection() {
        return fields;
    }

    public Integer limitValue() {
        return limit;
    }

    public Integer offsetValue() {
        return offset;
    }
}
