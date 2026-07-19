package com.platform.core.security;

import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.TableDefinition;
import com.platform.core.query.QueryMatcher;
import com.platform.core.query.QueryNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure ACL decision logic. Given the rules for a table's inheritance chain
 * and a user, decides access. Kept free of persistence (AclService owns
 * loading/caching) so the resolution-order rules can be unit-tested directly.
 *
 * Resolution order — most specific match wins, and only the winning level is
 * evaluated:
 *
 *   1. field-level rules on the exact table (field checks only)
 *   2. table-level rules on the exact table
 *   3. the same two steps for each ancestor, walking up the super_class chain
 *
 * Within the winning level, access is granted if ANY rule passes. A rule
 * passes when the user holds any of its roles (or it lists none) and its
 * condition matches the record. No rules anywhere means DENY — the platform
 * default posture is closed, except for admins, who bypass evaluation
 * entirely (checked by callers via {@link UserContext#isAdmin()} before
 * reaching here, and again defensively in these methods).
 */
@Component
public class AclEvaluator {

    private final SchemaCache cache;
    private final QueryMatcher matcher;

    public AclEvaluator(SchemaCache cache, QueryMatcher matcher) {
        this.cache = cache;
        this.matcher = matcher;
    }

    /**
     * Can {@code user} perform {@code operation} on the record as a whole?
     * {@code record} may be null when there is no record to evaluate against
     * (e.g. UI capability hints); condition rules then count as passing.
     */
    public boolean allowsOperation(Map<UUID, List<AclRule>> rulesByTable, TableDefinition table,
                                   String operation, UserContext user, Map<String, Object> record) {
        if (user.isAdmin()) {
            return true;
        }
        List<AclRule> level = winningLevel(rulesByTable, table, operation, null);
        return anyRulePasses(level, table, user, record);
    }

    /**
     * Is {@code field} accessible for {@code operation}? Field-level rules
     * are an additional restriction on top of table-level access: if no
     * field-level rule exists anywhere in the chain, the field inherits the
     * table's decision (which callers check via allowsOperation).
     */
    public boolean allowsField(Map<UUID, List<AclRule>> rulesByTable, TableDefinition table,
                               String operation, String field, UserContext user,
                               Map<String, Object> record) {
        if (user.isAdmin()) {
            return true;
        }
        List<AclRule> level = fieldLevel(rulesByTable, table, operation, field);
        if (level.isEmpty()) {
            return true; // no field rule → table-level decision governs
        }
        return anyRulePasses(level, table, user, record);
    }

    /** Whether any field-level rule exists for this field (cheap pre-filter). */
    public boolean hasFieldRules(Map<UUID, List<AclRule>> rulesByTable, TableDefinition table,
                                 String operation, String field) {
        return !fieldLevel(rulesByTable, table, operation, field).isEmpty();
    }

    /**
     * Read decision for row filtering. Conditions of passing rules are OR-ed
     * — holding any role that grants unconditional read means Allow; only
     * conditional grants produce a Restrict whose AST the record layer ANDs
     * into the WHERE clause.
     */
    public AclDecision readDecision(Map<UUID, List<AclRule>> rulesByTable, TableDefinition table,
                                    UserContext user) {
        if (user.isAdmin()) {
            return new AclDecision.Allow();
        }
        List<AclRule> level = winningLevel(rulesByTable, table, "read", null);
        List<QueryNode> conditions = new ArrayList<>();
        for (AclRule rule : level) {
            if (!rule.roleSatisfiedBy(user)) {
                continue;
            }
            if (rule.condition() == null || rule.condition() instanceof QueryNode.MatchAll) {
                return new AclDecision.Allow();
            }
            conditions.add(rule.condition());
        }
        if (conditions.isEmpty()) {
            return new AclDecision.Deny();
        }
        return new AclDecision.Restrict(
                conditions.size() == 1 ? conditions.get(0) : new QueryNode.Or(conditions));
    }

    // ------------------------------------------------------------------

    /**
     * The first non-empty rule level walking from most to least specific.
     * With field == null only table-level rules are considered.
     */
    private List<AclRule> winningLevel(Map<UUID, List<AclRule>> rulesByTable, TableDefinition table,
                                       String operation, String field) {
        List<TableDefinition> chain = cache.chain(table);
        // Walk leaf-first: the exact table is the most specific.
        for (int i = chain.size() - 1; i >= 0; i--) {
            List<AclRule> tableRules = rulesByTable.getOrDefault(chain.get(i).sysId(), List.of());
            if (field != null) {
                List<AclRule> fieldRules = filter(tableRules, operation, field);
                if (!fieldRules.isEmpty()) {
                    return fieldRules;
                }
            }
            List<AclRule> level = filter(tableRules, operation, null);
            if (!level.isEmpty()) {
                return level;
            }
        }
        return List.of();
    }

    /** Field-level rules only: exact table first, then up the chain. */
    private List<AclRule> fieldLevel(Map<UUID, List<AclRule>> rulesByTable, TableDefinition table,
                                     String operation, String field) {
        List<TableDefinition> chain = cache.chain(table);
        for (int i = chain.size() - 1; i >= 0; i--) {
            List<AclRule> fieldRules =
                    filter(rulesByTable.getOrDefault(chain.get(i).sysId(), List.of()), operation, field);
            if (!fieldRules.isEmpty()) {
                return fieldRules;
            }
        }
        return List.of();
    }

    private static List<AclRule> filter(List<AclRule> rules, String operation, String field) {
        return rules.stream()
                .filter(AclRule::active)
                .filter(r -> r.operation().equals(operation))
                .filter(r -> field == null ? r.tableLevel() : field.equals(r.field()))
                .toList();
    }

    private boolean anyRulePasses(List<AclRule> level, TableDefinition table, UserContext user,
                                  Map<String, Object> record) {
        for (AclRule rule : level) {
            if (!rule.roleSatisfiedBy(user)) {
                continue;
            }
            if (rule.condition() == null || record == null
                    || matcher.matches(table, rule.condition(), record)) {
                return true;
            }
        }
        return false;
    }
}
