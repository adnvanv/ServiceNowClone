package com.platform.core.ddl;

import com.platform.core.metadata.FieldType;

import java.util.List;

import static com.platform.core.ddl.IdentifierValidator.quote;

/**
 * Pure string-building of DDL statements. No JDBC in here — that separation
 * keeps this class trivially unit-testable and makes SchemaMutationService
 * the only place that actually executes schema changes.
 *
 * Every identifier that reaches this class has already passed
 * IdentifierValidator (platform-owned names like sys_id are constants), and
 * every identifier is emitted double-quoted and schema-qualified.
 */
public final class DdlGenerator {

    /**
     * A column to generate DDL for. referenceTableName is the physical name
     * of the referenced table (already validated), null unless type is
     * REFERENCE.
     */
    public record ColumnSpec(String element, FieldType type, Integer maxLength, String referenceTableName) {
    }

    /**
     * CREATE TABLE for a root table (no super class). Root tables own the
     * full set of base columns plus sys_class_name, which records the actual
     * class of each row so queries against a parent table can tell which
     * child a row belongs to. Storing it costs one small column; computing it
     * would cost a LEFT JOIN per child table on every read.
     */
    public static String createRootTable(String schema, String table, List<ColumnSpec> columns) {
        StringBuilder sql = new StringBuilder();
        sql.append("create table ").append(qualified(schema, table)).append(" (\n");
        sql.append("    \"sys_id\" uuid primary key default gen_random_uuid(),\n");
        sql.append("    \"sys_created_on\" timestamptz not null default now(),\n");
        sql.append("    \"sys_created_by\" text,\n");
        sql.append("    \"sys_updated_on\" timestamptz not null default now(),\n");
        sql.append("    \"sys_updated_by\" text,\n");
        sql.append("    \"sys_mod_count\" integer not null default 0,\n");
        sql.append("    \"sys_class_name\" varchar(64) not null default '").append(table).append("'");
        for (ColumnSpec c : columns) {
            sql.append(",\n    ").append(columnDefinition(schema, c));
        }
        sql.append("\n)");
        return sql.toString();
    }

    /**
     * CREATE TABLE for a child table in a joined-inheritance chain: only the
     * child's own columns, plus a sys_id that is both primary key and FK to
     * the direct parent. The cascade means deleting the parent row deletes
     * the whole record — the record layer only ever deletes from the root.
     */
    public static String createChildTable(String schema, String table, String parentTable,
                                          List<ColumnSpec> columns) {
        StringBuilder sql = new StringBuilder();
        sql.append("create table ").append(qualified(schema, table)).append(" (\n");
        sql.append("    \"sys_id\" uuid primary key references ")
                .append(qualified(schema, parentTable)).append("(\"sys_id\") on delete cascade");
        for (ColumnSpec c : columns) {
            sql.append(",\n    ").append(columnDefinition(schema, c));
        }
        sql.append("\n)");
        return sql.toString();
    }

    public static String addColumn(String schema, String table, ColumnSpec column) {
        return "alter table " + qualified(schema, table)
                + " add column " + columnDefinition(schema, column);
    }

    /**
     * Mandatory does NOT translate to NOT NULL: adding a NOT NULL column to a
     * table with existing rows would fail, and (as in ServiceNow) mandatory
     * is a UI/API-level contract enforced by RecordService. Default values
     * are likewise applied by the record layer so that they behave uniformly
     * whether set at create-table time or added later.
     */
    private static String columnDefinition(String schema, ColumnSpec c) {
        StringBuilder def = new StringBuilder(quote(c.element())).append(' ').append(c.type().pgType(c.maxLength()));
        if (c.type() == FieldType.REFERENCE) {
            // Real FK so the database itself guarantees references point at
            // existing records. "on delete set null" over cascade: deleting a
            // referenced record should not silently destroy the records that
            // pointed at it.
            def.append(" references ").append(qualified(schema, c.referenceTableName()))
                    .append("(\"sys_id\") on delete set null");
        }
        return def.toString();
    }

    private static String qualified(String schema, String table) {
        return quote(schema) + '.' + quote(table);
    }

    private DdlGenerator() {
    }
}
