package com.platform.core.query;

import com.platform.core.error.BadRequestException;
import com.platform.core.query.EncodedQueryTokenizer.Token;

import java.util.ArrayList;
import java.util.List;

/**
 * Recursive-descent parser over the token stream, producing a QueryNode AST.
 *
 * Precedence, tightest-binding first:
 *
 *   1. ^OR  — binds to the immediately preceding condition, so
 *             a^b^ORc^d  parses as  a AND (b OR c) AND d
 *   2. ^    — AND
 *   3. ^NQ  — a top-level OR between whole sub-queries
 *
 * Grammar:
 *   query    := andChain ( NQ andChain )*
 *   andChain := orGroup ( AND orGroup )*
 *   orGroup  := condition ( OR condition )*
 *
 * The parser is intentionally strict: a leading ^OR/^NQ, an empty segment or
 * a malformed condition is a 400, not a guess at intent.
 */
public final class EncodedQueryParser {

    /** Parse an encoded query; null/blank parses to MatchAll. */
    public static QueryNode parse(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            return new QueryNode.MatchAll();
        }
        return new EncodedQueryParser(EncodedQueryTokenizer.tokenize(encoded.trim())).parseQuery();
    }

    private final List<Token> tokens;
    private int pos = 0;

    private EncodedQueryParser(List<Token> tokens) {
        this.tokens = tokens;
    }

    private QueryNode parseQuery() {
        List<QueryNode> groups = new ArrayList<>();
        groups.add(parseAndChain());
        while (peek() instanceof Token.NewQuery) {
            pos++;
            groups.add(parseAndChain());
        }
        if (pos < tokens.size()) {
            throw new BadRequestException("Malformed query", "Unexpected token at position " + pos);
        }
        return groups.size() == 1 ? groups.get(0) : new QueryNode.Or(groups);
    }

    private QueryNode parseAndChain() {
        List<QueryNode> terms = new ArrayList<>();
        terms.add(parseOrGroup());
        while (peek() instanceof Token.And) {
            pos++;
            terms.add(parseOrGroup());
        }
        return terms.size() == 1 ? terms.get(0) : new QueryNode.And(terms);
    }

    private QueryNode parseOrGroup() {
        List<QueryNode> alts = new ArrayList<>();
        alts.add(parseCondition());
        while (peek() instanceof Token.Or) {
            pos++;
            alts.add(parseCondition());
        }
        return alts.size() == 1 ? alts.get(0) : new QueryNode.Or(alts);
    }

    private QueryNode parseCondition() {
        Token t = peek();
        if (!(t instanceof Token.Condition c)) {
            throw new BadRequestException("Malformed query",
                    "Expected a condition at token position " + pos);
        }
        pos++;
        return new QueryNode.Condition(c.field(), c.operator(), c.rawValue());
    }

    private Token peek() {
        return pos < tokens.size() ? tokens.get(pos) : null;
    }
}
