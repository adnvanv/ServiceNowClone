package com.platform.core.query;

import java.util.List;

/**
 * The AST an encoded query parses into. Two consumers walk it: SqlBuilder
 * compiles it to a parameterised WHERE clause, and QueryMatcher evaluates it
 * in memory against a single record (used by ACL write/delete conditions).
 * Keeping the AST this small is what lets both stay simple.
 */
public sealed interface QueryNode {

    /** A single comparison. {@code rawValue} is the still-unparsed value text. */
    record Condition(String field, Operator operator, String rawValue) implements QueryNode {
    }

    record And(List<QueryNode> children) implements QueryNode {
    }

    record Or(List<QueryNode> children) implements QueryNode {
    }

    /** The empty query: matches everything. */
    record MatchAll() implements QueryNode {
    }
}
