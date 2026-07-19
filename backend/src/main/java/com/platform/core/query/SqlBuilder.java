package com.platform.core.query;

import com.platform.core.error.BadRequestException;
import com.platform.core.metadata.BaseColumns;
import com.platform.core.metadata.FieldDefinition;
import com.platform.core.metadata.FieldType;
import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.TableDefinition;
import com.platform.core.tenant.TenantContext;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.platform.core.ddl.IdentifierValidator.quote;

/**
 * Compiles a table + QueryNode AST into SQL.
 *
 * Everything structural (identifiers, aliases) is validated against the
 * schema cache and quoted; everything data (comparison values) is bound as a
 * named parameter. There is no code path here that concatenates a user value
 * into SQL text.
 *
 * The FROM clause reconstructs a record from its joined-inheritance chain:
 * reading "incident extends task" produces
 *   from "public"."task" "task"
 *   join "public"."incident" "incident" on "incident"."sys_id" = "task"."sys_id"
 * Base/audit columns are always taken from the root table, which is the
 * canonical owner of a record's identity.
 *
 * Dot-walked fields (assigned_to.department, one level in v1) become LEFT
 * JOINs with a "dw$" alias prefix; '$' cannot appear in a validated
 * identifier, so these aliases can never collide with real table names. The
 * resolver is a single method (resolveField), so extending walking beyond one
 * level later means generalising that method, not rewriting the builder.
 */
@Component
public class SqlBuilder {

    public record OrderBy(String field, boolean desc) {
    }

    public record CompiledQuery(String sql, MapSqlParameterSource params) {
    }

    private final SchemaCache cache;
    private final TenantContext tenant;

    public SqlBuilder(SchemaCache cache, TenantContext tenant) {
        this.cache = cache;
        this.tenant = tenant;
    }

    /**
     * Build a SELECT for {@code table}.
     *
     * @param fields  projection (dictionary fields, base columns, one-level
     *                dot-walks); null selects every visible field
     * @param displayValueFields reference fields for which a
     *                "<element>__display" column carrying the referenced
     *                record's display field should be added
     */
    public CompiledQuery buildSelect(TableDefinition table, QueryNode where, List<String> fields,
                                     List<String> displayValueFields, List<OrderBy> orderBys,
                                     Integer limit, Integer offset) {
        Ctx ctx = new Ctx(table);

        List<String> select = new ArrayList<>();
        for (String f : (fields != null ? fields : defaultProjection(table))) {
            ResolvedField rf = resolveField(ctx, f);
            select.add(rf.expr() + " as " + quote(f));
        }
        for (String refField : displayValueFields) {
            select.add(displayExpression(ctx, refField) + " as " + quote(refField + "__display"));
        }

        StringBuilder sql = new StringBuilder("select ").append(String.join(", ", select));
        appendFromWhere(ctx, sql, where);

        List<String> orderExprs = new ArrayList<>();
        for (OrderBy o : (orderBys == null || orderBys.isEmpty() ? DEFAULT_ORDER : orderBys)) {
            ResolvedField rf = resolveField(ctx, o.field());
            orderExprs.add(rf.expr() + (o.desc() ? " desc" : " asc"));
        }
        // sys_id tiebreak keeps pagination stable when the sort key has duplicates.
        orderExprs.add(ctx.rootAlias() + ".\"sys_id\" asc");
        sql.append(" order by ").append(String.join(", ", orderExprs));

        if (limit != null) {
            sql.append(" limit :__limit");
            ctx.params.addValue("__limit", limit);
        }
        if (offset != null && offset > 0) {
            sql.append(" offset :__offset");
            ctx.params.addValue("__offset", offset);
        }
        return new CompiledQuery(sql.toString(), ctx.params);
    }

    public CompiledQuery buildCount(TableDefinition table, QueryNode where) {
        Ctx ctx = new Ctx(table);
        StringBuilder sql = new StringBuilder("select count(*)");
        appendFromWhere(ctx, sql, where);
        return new CompiledQuery(sql.toString(), ctx.params);
    }

    private void appendFromWhere(Ctx ctx, StringBuilder sql, QueryNode where) {
        // WHERE is compiled before FROM is emitted because compiling
        // conditions can add dot-walk joins to the context.
        String whereSql = where != null ? compileNode(ctx, where) : null;

        List<TableDefinition> chain = cache.chain(ctx.table);
        sql.append(" from ").append(qualified(chain.get(0).name())).append(' ').append(alias(chain.get(0)));
        for (int i = 1; i < chain.size(); i++) {
            String a = alias(chain.get(i));
            sql.append(" join ").append(qualified(chain.get(i).name())).append(' ').append(a)
                    .append(" on ").append(a).append(".\"sys_id\" = ").append(ctx.rootAlias()).append(".\"sys_id\"");
        }
        for (String join : ctx.dotWalkJoins.values()) {
            sql.append(join);
        }
        if (whereSql != null && !whereSql.isEmpty()) {
            sql.append(" where ").append(whereSql);
        }
    }

    private static final List<OrderBy> DEFAULT_ORDER = List.of(new OrderBy("sys_created_on", true));

    /** Every visible field: base columns then dictionary fields, root first. */
    public List<String> defaultProjection(TableDefinition table) {
        List<String> fields = new ArrayList<>(List.of(
                BaseColumns.SYS_ID, BaseColumns.SYS_CLASS_NAME,
                BaseColumns.SYS_CREATED_ON, BaseColumns.SYS_CREATED_BY,
                BaseColumns.SYS_UPDATED_ON, BaseColumns.SYS_UPDATED_BY,
                BaseColumns.SYS_MOD_COUNT));
        for (FieldDefinition f : cache.fieldsInChain(table)) {
            fields.add(f.element());
        }
        return fields;
    }

    // ------------------------------------------------------------------
    // WHERE compilation
    // ------------------------------------------------------------------

    private String compileNode(Ctx ctx, QueryNode node) {
        return switch (node) {
            case QueryNode.MatchAll ignored -> "";
            case QueryNode.And and -> combine(ctx, and.children(), " and ");
            case QueryNode.Or or -> combine(ctx, or.children(), " or ");
            case QueryNode.Condition c -> compileCondition(ctx, c);
        };
    }

    private String combine(Ctx ctx, List<QueryNode> children, String connector) {
        List<String> parts = new ArrayList<>();
        for (QueryNode child : children) {
            String part = compileNode(ctx, child);
            if (!part.isEmpty()) {
                parts.add(part);
            }
        }
        if (parts.isEmpty()) {
            return "";
        }
        return parts.size() == 1 ? parts.get(0) : "(" + String.join(connector, parts) + ")";
    }

    private String compileCondition(Ctx ctx, QueryNode.Condition c) {
        ResolvedField rf = resolveField(ctx, c.field());
        String col = rf.expr();
        FieldType type = rf.type();
        String raw = c.rawValue();

        return switch (c.operator()) {
            case EQUALS -> col + " = " + bind(ctx, type, raw, c.field());
            // != and NOT IN also match NULL: "everything except value X"
            // should include records where the field is empty. Plain SQL
            // three-valued logic would silently drop those rows.
            case NOT_EQUALS -> "(" + col + " != " + bind(ctx, type, raw, c.field())
                    + " or " + col + " is null)";
            case GREATER_THAN -> col + " > " + bind(ctx, type, raw, c.field());
            case LESS_THAN -> col + " < " + bind(ctx, type, raw, c.field());
            case GTE -> col + " >= " + bind(ctx, type, raw, c.field());
            case LTE -> col + " <= " + bind(ctx, type, raw, c.field());
            case LIKE -> textMatch(ctx, col, type, "%" + escapeLike(raw) + "%", c.field());
            case STARTS_WITH -> textMatch(ctx, col, type, escapeLike(raw) + "%", c.field());
            case ENDS_WITH -> textMatch(ctx, col, type, "%" + escapeLike(raw), c.field());
            case IN -> col + " in (" + bindList(ctx, type, raw, c.field()) + ")";
            case NOT_IN -> "(" + col + " not in (" + bindList(ctx, type, raw, c.field()) + ")"
                    + " or " + col + " is null)";
            case IS_EMPTY -> isTextual(type)
                    ? "(" + col + " is null or " + col + " = '')"
                    : col + " is null";
            case IS_NOT_EMPTY -> isTextual(type)
                    ? "(" + col + " is not null and " + col + " != '')"
                    : col + " is not null";
            case BETWEEN -> {
                String[] bounds = raw.split("@", -1);
                if (bounds.length != 2) {
                    throw new BadRequestException("BETWEEN requires two values separated by '@'",
                            "Field '" + c.field() + "', got '" + raw + "'");
                }
                yield col + " between " + bind(ctx, type, bounds[0], c.field())
                        + " and " + bind(ctx, type, bounds[1], c.field());
            }
        };
    }

    private String textMatch(Ctx ctx, String col, FieldType type, String pattern, String field) {
        if (!isTextual(type)) {
            throw new BadRequestException(
                    "Operator not applicable to field '" + field + "'",
                    "LIKE/STARTSWITH/ENDSWITH require a text-like field");
        }
        return col + " ilike " + bind(ctx, type, pattern, field) + " escape '\\'";
    }

    private static boolean isTextual(FieldType type) {
        return type == FieldType.STRING || type == FieldType.TEXT || type == FieldType.CHOICE;
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private String bind(Ctx ctx, FieldType type, String raw, String field) {
        String name = "p" + ctx.paramCounter++;
        ctx.params.addValue(name, ValueCoercion.coerce(type, raw, field));
        return ":" + name;
    }

    private String bindList(Ctx ctx, FieldType type, String raw, String field) {
        List<Object> values = new ArrayList<>();
        for (String part : raw.split(",", -1)) {
            values.add(ValueCoercion.coerce(type, part, field));
        }
        String name = "p" + ctx.paramCounter++;
        ctx.params.addValue(name, values);
        return ":" + name;
    }

    // ------------------------------------------------------------------
    // Field resolution
    // ------------------------------------------------------------------

    private record ResolvedField(String expr, FieldType type) {
    }

    /**
     * Resolve a (possibly dot-walked) field name to a SQL expression and its
     * logical type, registering any joins it needs. Unknown fields are a 400.
     */
    private ResolvedField resolveField(Ctx ctx, String name) {
        int dot = name.indexOf('.');
        if (dot < 0) {
            return resolveLocal(ctx, name);
        }
        // v1: exactly one level of dot-walking.
        String refElement = name.substring(0, dot);
        String targetField = name.substring(dot + 1);
        if (targetField.contains(".")) {
            throw new BadRequestException("Unsupported field '" + name + "'",
                    "Dot-walking is limited to one level in v1");
        }

        FieldDefinition refField = cache.field(ctx.table, refElement)
                .orElseThrow(() -> unknownField(ctx.table, refElement));
        if (refField.type() != FieldType.REFERENCE) {
            throw new BadRequestException("Cannot dot-walk through '" + refElement + "'",
                    "'" + refElement + "' is not a reference field");
        }
        TableDefinition target = cache.byId(refField.referenceTableId());
        String dwAlias = registerDotWalkJoin(ctx, refField, target);

        if (BaseColumns.isBase(targetField)) {
            return new ResolvedField(dwAlias + '.' + quote(targetField),
                    ValueCoercion.baseColumnType(targetField));
        }
        // Fields inherited by the target table would live on the target's
        // parent tables; joining the target's whole chain is the natural
        // extension point for deeper walking and stays out of v1.
        FieldDefinition f = target.ownField(targetField)
                .orElseThrow(() -> unknownField(target, targetField));
        return new ResolvedField(dwAlias + '.' + quote(targetField), f.type());
    }

    private ResolvedField resolveLocal(Ctx ctx, String name) {
        if (BaseColumns.isBase(name)) {
            return new ResolvedField(ctx.rootAlias() + '.' + quote(name),
                    ValueCoercion.baseColumnType(name));
        }
        FieldDefinition f = cache.field(ctx.table, name)
                .orElseThrow(() -> unknownField(ctx.table, name));
        TableDefinition owner = cache.owningTable(ctx.table, name).orElseThrow();
        return new ResolvedField(alias(owner) + '.' + quote(name), f.type());
    }

    /** The referenced record's human-readable display expression, joined in. */
    private String displayExpression(Ctx ctx, String refElement) {
        FieldDefinition refField = cache.field(ctx.table, refElement)
                .orElseThrow(() -> unknownField(ctx.table, refElement));
        if (refField.type() != FieldType.REFERENCE) {
            throw new BadRequestException("Field '" + refElement + "' is not a reference field");
        }
        TableDefinition target = cache.byId(refField.referenceTableId());
        String dwAlias = registerDotWalkJoin(ctx, refField, target);
        return dwAlias + '.' + quote(displayField(target));
    }

    /** display=true field if declared, else a sensible fallback. */
    public String displayField(TableDefinition table) {
        Optional<FieldDefinition> display = cache.fieldsInChain(table).stream()
                .filter(FieldDefinition::display).findFirst();
        if (display.isPresent()) {
            return display.get().element();
        }
        for (String candidate : List.of("name", "number", "short_description")) {
            if (cache.field(table, candidate).isPresent()) {
                return candidate;
            }
        }
        return BaseColumns.SYS_ID;
    }

    private String registerDotWalkJoin(Ctx ctx, FieldDefinition refField, TableDefinition target) {
        String dwAlias = quote("dw$" + refField.element());
        ctx.dotWalkJoins.computeIfAbsent(refField.element(), k -> {
            TableDefinition owner = cache.owningTable(ctx.table, refField.element()).orElseThrow();
            return " left join " + qualified(target.name()) + ' ' + dwAlias
                    + " on " + dwAlias + ".\"sys_id\" = " + alias(owner) + '.' + quote(refField.element());
        });
        return dwAlias;
    }

    private BadRequestException unknownField(TableDefinition table, String field) {
        return new BadRequestException(
                "Unknown field '" + field + "' on table '" + table.name() + "'");
    }

    private String qualified(String table) {
        return quote(tenant.schema()) + '.' + quote(table);
    }

    private static String alias(TableDefinition table) {
        return quote(table.name());
    }

    /** Mutable state accumulated while compiling one statement. */
    private final class Ctx {
        final TableDefinition table;
        final MapSqlParameterSource params = new MapSqlParameterSource();
        final Map<String, String> dotWalkJoins = new LinkedHashMap<>();
        int paramCounter = 0;

        Ctx(TableDefinition table) {
            this.table = table;
        }

        String rootAlias() {
            return alias(cache.root(table));
        }
    }
}
