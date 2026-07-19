package com.platform.core.query;

import com.platform.core.error.BadRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * Lexes an encoded query string into a flat token stream for the parser.
 *
 * The language has exactly one structural character, '^', which introduces
 * the connectors AND ("^"), OR ("^OR") and NEW-QUERY ("^NQ"). Everything
 * between connectors is a condition, which this tokenizer further lexes into
 * (field, operator, value):
 *
 *   - field: longest run of [a-z0-9_.] (dots for dot-walking)
 *   - operator: longest literal operator token at the current position —
 *     longest-first matters so ">=" beats ">" and "NOT IN" beats "IN"
 *   - value: the rest of the segment, verbatim
 *
 * There is deliberately no escaping mechanism for '^' inside values; that is
 * a known limitation inherited from the source language.
 */
final class EncodedQueryTokenizer {

    sealed interface Token {
        record And() implements Token {
        }

        record Or() implements Token {
        }

        record NewQuery() implements Token {
        }

        record Condition(String field, Operator operator, String rawValue) implements Token {
        }
    }

    static List<Token> tokenize(String encoded) {
        List<Token> tokens = new ArrayList<>();
        // Split retaining empty segments so "a=1^^b=2" or a trailing '^' is
        // caught as malformed rather than silently swallowed.
        String[] segments = encoded.split("\\^", -1);
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (i > 0) {
                if (segment.startsWith("OR")) {
                    tokens.add(new Token.Or());
                    segment = segment.substring(2);
                } else if (segment.startsWith("NQ")) {
                    tokens.add(new Token.NewQuery());
                    segment = segment.substring(2);
                } else {
                    tokens.add(new Token.And());
                }
            }
            if (segment.isEmpty()) {
                throw new BadRequestException("Malformed query: empty condition",
                        "Near segment " + (i + 1) + " of '" + encoded + "'");
            }
            tokens.add(lexCondition(segment));
        }
        return tokens;
    }

    private static Token.Condition lexCondition(String segment) {
        int pos = 0;
        while (pos < segment.length() && isFieldChar(segment.charAt(pos))) {
            pos++;
        }
        if (pos == 0) {
            throw new BadRequestException("Malformed query condition: '" + segment + "'",
                    "Expected a field name");
        }
        String field = segment.substring(0, pos);

        Operator operator = null;
        for (Operator candidate : Operator.BY_TOKEN_LENGTH_DESC) {
            if (segment.startsWith(candidate.token(), pos)) {
                operator = candidate;
                break;
            }
        }
        if (operator == null) {
            throw new BadRequestException("Malformed query condition: '" + segment + "'",
                    "Expected an operator after '" + field + "'");
        }

        String value = segment.substring(pos + operator.token().length());
        if (operator.unary() && !value.isEmpty()) {
            throw new BadRequestException("Malformed query condition: '" + segment + "'",
                    operator.token() + " takes no value");
        }
        if (!operator.unary() && value.isEmpty()) {
            throw new BadRequestException("Malformed query condition: '" + segment + "'",
                    operator.token() + " requires a value");
        }
        return new Token.Condition(field, operator, value);
    }

    private static boolean isFieldChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '.';
    }

    private EncodedQueryTokenizer() {
    }
}
