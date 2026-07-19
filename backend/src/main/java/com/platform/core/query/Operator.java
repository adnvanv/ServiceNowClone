package com.platform.core.query;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * The operators of the encoded query language. {@code token} is the literal
 * text that appears between field and value in an encoded query
 * (e.g. {@code priorityIN1,2,3}).
 */
public enum Operator {

    EQUALS("="),
    NOT_EQUALS("!="),
    GREATER_THAN(">"),
    LESS_THAN("<"),
    GTE(">="),
    LTE("<="),
    LIKE("LIKE"),
    STARTS_WITH("STARTSWITH"),
    ENDS_WITH("ENDSWITH"),
    IN("IN"),
    NOT_IN("NOT IN"),
    IS_EMPTY("ISEMPTY", true),
    IS_NOT_EMPTY("ISNOTEMPTY", true),
    BETWEEN("BETWEEN");

    private final String token;
    private final boolean unary;

    Operator(String token) {
        this(token, false);
    }

    Operator(String token, boolean unary) {
        this.token = token;
        this.unary = unary;
    }

    public String token() {
        return token;
    }

    /** Unary operators (ISEMPTY / ISNOTEMPTY) take no value. */
    public boolean unary() {
        return unary;
    }

    /**
     * All operators sorted longest-token-first. The tokenizer matches against
     * this list so that e.g. ">=" wins over ">" and "ISNOTEMPTY" wins over
     * "IN" ("ISNOTEMPTY" contains no "IN" prefix, but "IN" would match inside
     * "INT..." style values if tried in the wrong order).
     */
    public static final List<Operator> BY_TOKEN_LENGTH_DESC = Arrays.stream(values())
            .sorted(Comparator.comparingInt((Operator o) -> o.token.length()).reversed())
            .toList();
}
