package com.platform.core.query;

import com.platform.core.error.BadRequestException;
import com.platform.core.metadata.BaseColumns;
import com.platform.core.metadata.FieldDefinition;
import com.platform.core.metadata.FieldType;
import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.TableDefinition;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;

/**
 * Evaluates a QueryNode AST against a single in-memory record. This is the
 * second consumer of the AST (SqlBuilder being the first): ACL conditions for
 * create/write/delete are checked against the record being mutated without a
 * round-trip to the database.
 *
 * Semantics deliberately mirror SqlBuilder's WHERE compilation — e.g. != and
 * NOT IN also match empty values, LIKE is a case-insensitive contains — so a
 * condition behaves identically whether it filters rows in SQL or gates a
 * mutation in memory. Dot-walked fields are not supported here (they would
 * need a DB lookup); ACL conditions are limited to local fields in v1.
 */
@Component
public class QueryMatcher {

    private final SchemaCache cache;

    public QueryMatcher(SchemaCache cache) {
        this.cache = cache;
    }

    public boolean matches(TableDefinition table, QueryNode node, Map<String, Object> values) {
        return switch (node) {
            case QueryNode.MatchAll ignored -> true;
            case QueryNode.And and -> and.children().stream().allMatch(c -> matches(table, c, values));
            case QueryNode.Or or -> or.children().stream().anyMatch(c -> matches(table, c, values));
            case QueryNode.Condition c -> matchesCondition(table, c, values);
        };
    }

    private boolean matchesCondition(TableDefinition table, QueryNode.Condition c,
                                     Map<String, Object> values) {
        if (c.field().contains(".")) {
            throw new BadRequestException(
                    "Dot-walked field '" + c.field() + "' is not supported in this condition",
                    "Conditions evaluated against a single record must use local fields");
        }
        FieldType type = fieldType(table, c.field());
        Object actual = values.get(c.field());

        return switch (c.operator()) {
            case EQUALS -> compare(actual, coerce(type, c), type) == 0;
            case NOT_EQUALS -> isEmpty(actual) || compare(actual, coerce(type, c), type) != 0;
            case GREATER_THAN -> !isEmpty(actual) && compare(actual, coerce(type, c), type) > 0;
            case LESS_THAN -> !isEmpty(actual) && compare(actual, coerce(type, c), type) < 0;
            case GTE -> !isEmpty(actual) && compare(actual, coerce(type, c), type) >= 0;
            case LTE -> !isEmpty(actual) && compare(actual, coerce(type, c), type) <= 0;
            case LIKE -> text(actual).toLowerCase().contains(c.rawValue().toLowerCase());
            case STARTS_WITH -> text(actual).toLowerCase().startsWith(c.rawValue().toLowerCase());
            case ENDS_WITH -> text(actual).toLowerCase().endsWith(c.rawValue().toLowerCase());
            case IN -> Arrays.stream(c.rawValue().split(",", -1))
                    .anyMatch(v -> !isEmpty(actual)
                            && compare(actual, ValueCoercion.coerce(type, v, c.field()), type) == 0);
            case NOT_IN -> isEmpty(actual) || Arrays.stream(c.rawValue().split(",", -1))
                    .noneMatch(v -> compare(actual, ValueCoercion.coerce(type, v, c.field()), type) == 0);
            case IS_EMPTY -> isEmpty(actual);
            case IS_NOT_EMPTY -> !isEmpty(actual);
            case BETWEEN -> {
                String[] bounds = c.rawValue().split("@", -1);
                if (bounds.length != 2) {
                    throw new BadRequestException("BETWEEN requires two values separated by '@'");
                }
                yield !isEmpty(actual)
                        && compare(actual, ValueCoercion.coerce(type, bounds[0], c.field()), type) >= 0
                        && compare(actual, ValueCoercion.coerce(type, bounds[1], c.field()), type) <= 0;
            }
        };
    }

    private FieldType fieldType(TableDefinition table, String field) {
        if (BaseColumns.isBase(field)) {
            return ValueCoercion.baseColumnType(field);
        }
        return cache.field(table, field)
                .map(FieldDefinition::type)
                .orElseThrow(() -> new BadRequestException(
                        "Unknown field '" + field + "' on table '" + table.name() + "'"));
    }

    private Object coerce(FieldType type, QueryNode.Condition c) {
        return ValueCoercion.coerce(type, c.rawValue(), c.field());
    }

    private static boolean isEmpty(Object v) {
        return v == null || (v instanceof String s && s.isEmpty());
    }

    private static String text(Object v) {
        return v == null ? "" : v.toString();
    }

    /**
     * Compare a stored value against a coerced query value. Numbers compare
     * numerically regardless of concrete class (JDBC may hand back Integer,
     * Long or BigDecimal for the same logical type).
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static int compare(Object actual, Object expected, FieldType type) {
        if (isEmpty(actual)) {
            // Only EQUALS reaches here with an empty actual: empty never equals a value.
            return expected == null ? 0 : -1;
        }
        if (actual instanceof Number a && expected instanceof Number b) {
            return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString()));
        }
        if (actual instanceof OffsetDateTime a && expected instanceof OffsetDateTime b) {
            return a.compareTo(b);
        }
        if (actual instanceof UUID || expected instanceof UUID) {
            return actual.toString().compareTo(expected.toString());
        }
        if (actual.getClass() == expected.getClass() && actual instanceof Comparable a) {
            return a.compareTo(expected);
        }
        return actual.toString().compareTo(expected.toString());
    }
}
