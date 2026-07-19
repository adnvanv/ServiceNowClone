package com.platform.core.security;

import com.platform.core.query.QueryNode;

/**
 * Outcome of evaluating read access for a user on a table, designed to be
 * injected into the query layer: Allow adds nothing, Restrict ANDs its
 * condition into the WHERE clause (denied rows are never fetched), Deny
 * short-circuits to an empty result.
 */
public sealed interface AclDecision {

    record Allow() implements AclDecision {
    }

    record Deny() implements AclDecision {
    }

    record Restrict(QueryNode condition) implements AclDecision {
    }
}
