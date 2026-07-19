package com.platform.core.ddl;

import com.platform.core.error.BadRequestException;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * The single gate every user-supplied identifier must pass before it can
 * appear in generated SQL.
 *
 * Table and column names cannot be bound as prepared-statement parameters, so
 * this class is the only defence against SQL injection on the DDL path. The
 * rules are therefore strict and dumb on purpose:
 *
 *  - must match ^[a-z][a-z0-9_]{0,58}$ (lowercase ASCII, digits, underscore;
 *    59 chars max keeps us under Postgres's 63-byte identifier limit with
 *    room for generated suffixes like _pkey)
 *  - must not be a Postgres reserved word
 *  - must not start with "sys_" — that namespace belongs to the platform
 *
 * Everything that passes is additionally always emitted double-quoted by
 * DdlGenerator / SqlBuilder, so even a future loosening of the character
 * rules could not silently open an injection hole.
 */
public final class IdentifierValidator {

    private static final Pattern VALID = Pattern.compile("^[a-z][a-z0-9_]{0,58}$");

    /**
     * Postgres reserved keywords (the "reserved" and "reserved, can be
     * function name" categories of the official keyword list). Unreserved
     * keywords are allowed — rejecting e.g. "state" or "number" would make
     * the platform useless — and are safe because identifiers are always
     * quoted in generated SQL.
     */
    static final Set<String> RESERVED = Set.of(
            "all", "analyse", "analyze", "and", "any", "array", "as", "asc",
            "asymmetric", "authorization", "binary", "both", "case", "cast",
            "check", "collate", "collation", "column", "concurrently",
            "constraint", "create", "cross", "current_catalog", "current_date",
            "current_role", "current_schema", "current_time",
            "current_timestamp", "current_user", "default", "deferrable",
            "desc", "distinct", "do", "else", "end", "except", "false",
            "fetch", "for", "foreign", "freeze", "from", "full", "grant",
            "group", "having", "ilike", "in", "initially", "inner",
            "intersect", "into", "is", "isnull", "join", "lateral", "leading",
            "left", "like", "limit", "localtime", "localtimestamp", "natural",
            "not", "notnull", "null", "offset", "on", "only", "or", "order",
            "outer", "overlaps", "placing", "primary", "references",
            "returning", "right", "select", "session_user", "similar", "some",
            "symmetric", "system_user", "table", "tablesample", "then", "to",
            "trailing", "true", "union", "unique", "user", "using",
            "variadic", "verbose", "when", "where", "window", "with");

    /**
     * Validate a user-supplied table or column name. Throws
     * {@link BadRequestException} with a message naming the offending
     * identifier; returns the identifier unchanged so calls can be inlined.
     */
    public static String validate(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new BadRequestException("Identifier must not be empty");
        }
        if (!VALID.matcher(identifier).matches()) {
            throw new BadRequestException(
                    "Invalid identifier '" + identifier + "'",
                    "Identifiers must match ^[a-z][a-z0-9_]{0,58}$ "
                            + "(lowercase letters, digits and underscores, starting with a letter, max 59 chars)");
        }
        if (identifier.startsWith("sys_")) {
            throw new BadRequestException(
                    "Invalid identifier '" + identifier + "'",
                    "Names starting with 'sys_' are reserved for the platform");
        }
        if (RESERVED.contains(identifier)) {
            throw new BadRequestException(
                    "Invalid identifier '" + identifier + "'",
                    "'" + identifier + "' is a reserved SQL keyword");
        }
        return identifier;
    }

    /**
     * Quote an identifier for inclusion in generated SQL. Only accepts
     * identifiers that are either platform-owned base columns/tables or have
     * passed {@link #validate}; the character class makes embedded quotes
     * impossible, but quoting is applied anyway as defence in depth.
     */
    public static String quote(String identifier) {
        if (identifier.contains("\"")) {
            // Unreachable for validated identifiers; kept as a hard stop in
            // case a future code path feeds an unvalidated name in here.
            throw new BadRequestException("Invalid identifier '" + identifier + "'");
        }
        return '"' + identifier + '"';
    }

    private IdentifierValidator() {
    }
}
