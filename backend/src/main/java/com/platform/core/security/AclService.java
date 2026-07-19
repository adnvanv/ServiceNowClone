package com.platform.core.security;

import com.platform.core.error.ForbiddenException;
import com.platform.core.metadata.TableDefinition;
import com.platform.core.query.EncodedQueryParser;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Loads and caches ACL rules, and is the facade the record layer calls for
 * every access decision. Like the schema cache, the rule set is tiny and
 * changes rarely, so it is cached wholesale and reloaded on change.
 */
@Service
public class AclService {

    private final NamedParameterJdbcTemplate jdbc;
    private final AclEvaluator evaluator;
    private volatile Map<UUID, List<AclRule>> rulesByTable = Map.of();
    private volatile boolean loaded = false;

    public AclService(NamedParameterJdbcTemplate jdbc, AclEvaluator evaluator) {
        this.jdbc = jdbc;
        this.evaluator = evaluator;
    }

    public synchronized void reload() {
        Map<UUID, Set<String>> rolesByAcl = new HashMap<>();
        jdbc.query("""
                select ar.acl_id, r.name
                from sys_acl_role ar join sys_role r on r.sys_id = ar.role_id
                """,
                rs -> {
                    rolesByAcl.computeIfAbsent(rs.getObject("acl_id", UUID.class), k -> new HashSet<>())
                            .add(rs.getString("name"));
                });

        Map<UUID, List<AclRule>> byTable = new HashMap<>();
        jdbc.query("""
                select sys_id, table_id, field, operation, condition, active
                from sys_security_acl where active = true
                """,
                rs -> {
                    UUID id = rs.getObject("sys_id", UUID.class);
                    String condition = rs.getString("condition");
                    AclRule rule = new AclRule(
                            id,
                            rs.getObject("table_id", UUID.class),
                            rs.getString("field"),
                            rs.getString("operation"),
                            condition != null && !condition.isBlank()
                                    ? EncodedQueryParser.parse(condition) : null,
                            Set.copyOf(rolesByAcl.getOrDefault(id, Set.of())),
                            true);
                    byTable.computeIfAbsent(rule.tableId(), k -> new ArrayList<>()).add(rule);
                });

        Map<UUID, List<AclRule>> frozen = new HashMap<>();
        byTable.forEach((k, v) -> frozen.put(k, List.copyOf(v)));
        rulesByTable = Map.copyOf(frozen);
        loaded = true;
    }

    private Map<UUID, List<AclRule>> rules() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    reload();
                }
            }
        }
        return rulesByTable;
    }

    // ------------------------------------------------------------------
    // Decisions
    // ------------------------------------------------------------------

    public AclDecision readDecision(TableDefinition table, UserContext user) {
        return evaluator.readDecision(rules(), table, user);
    }

    /** Throws 403 naming the operation if the table-level check fails. */
    public void checkOperation(TableDefinition table, String operation, UserContext user,
                               Map<String, Object> record) {
        if (!evaluator.allowsOperation(rules(), table, operation, user, record)) {
            throw new ForbiddenException(
                    "Operation '" + operation + "' on table '" + table.name() + "' is not allowed");
        }
    }

    /** Throws 403 naming operation and field if a field-level check fails. */
    public void checkField(TableDefinition table, String operation, String field, UserContext user,
                           Map<String, Object> record) {
        if (!evaluator.allowsField(rules(), table, operation, field, user, record)) {
            throw new ForbiddenException(
                    "Operation '" + operation + "' on field '" + field + "' of table '"
                            + table.name() + "' is not allowed");
        }
    }

    public boolean fieldReadable(TableDefinition table, String field, UserContext user,
                                 Map<String, Object> record) {
        return evaluator.allowsField(rules(), table, "read", field, user, record);
    }

    public boolean fieldWritable(TableDefinition table, String field, UserContext user,
                                 Map<String, Object> record) {
        return evaluator.allowsField(rules(), table, "write", field, user, record);
    }

    public boolean operationAllowed(TableDefinition table, String operation, UserContext user) {
        return evaluator.allowsOperation(rules(), table, operation, user, null);
    }

    /** Fields in the projection that have field-level read rules (cheap pre-filter). */
    public boolean hasFieldReadRules(TableDefinition table, String field) {
        return evaluator.hasFieldRules(rules(), table, "read", field);
    }

    // ------------------------------------------------------------------
    // Administration (used by the seeder; a future ACL admin UI would too)
    // ------------------------------------------------------------------

    public UUID createAcl(UUID tableId, String field, String operation, String condition,
                          List<UUID> roleIds, String actor) {
        if (condition != null && !condition.isBlank()) {
            EncodedQueryParser.parse(condition); // fail fast on broken conditions
        }
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into sys_security_acl (sys_id, table_id, field, operation, condition,
                                              active, sys_created_by, sys_updated_by)
                values (:id, :tableId, :field, :operation, :condition, true, :actor, :actor)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("tableId", tableId)
                        .addValue("field", field)
                        .addValue("operation", operation)
                        .addValue("condition", condition)
                        .addValue("actor", actor));
        for (UUID roleId : roleIds) {
            jdbc.update("""
                    insert into sys_acl_role (acl_id, role_id) values (:aclId, :roleId)
                    """,
                    Map.of("aclId", id, "roleId", roleId));
        }
        reload();
        return id;
    }
}
