package com.platform.core.security;

import com.platform.core.query.QueryNode;

import java.util.Set;
import java.util.UUID;

/**
 * One active row of sys_security_acl, with its roles resolved to names and
 * its condition pre-parsed to an AST at load time (fail-fast: a broken
 * condition is caught when ACLs load, not when a user happens to hit it).
 *
 * field == null means a table-level rule. An empty role set means "any
 * authenticated user" — the rule then gates on its condition alone.
 */
public record AclRule(
        UUID sysId,
        UUID tableId,
        String field,
        String operation,
        QueryNode condition,
        Set<String> roles,
        boolean active) {

    public boolean tableLevel() {
        return field == null;
    }

    public boolean roleSatisfiedBy(UserContext user) {
        return roles.isEmpty() || user.roles().stream().anyMatch(roles::contains);
    }
}
