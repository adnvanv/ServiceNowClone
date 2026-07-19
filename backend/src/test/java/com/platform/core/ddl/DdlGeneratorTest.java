package com.platform.core.ddl;

import com.platform.core.ddl.DdlGenerator.ColumnSpec;
import com.platform.core.metadata.FieldType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DdlGeneratorTest {

    @Test
    void rootTableCarriesBaseColumnsAndClassName() {
        String sql = DdlGenerator.createRootTable("public", "task", List.of(
                new ColumnSpec("short_description", FieldType.STRING, 160, null),
                new ColumnSpec("active", FieldType.BOOLEAN, null, null)));

        assertThat(sql)
                .startsWith("create table \"public\".\"task\"")
                .contains("\"sys_id\" uuid primary key default gen_random_uuid()")
                .contains("\"sys_mod_count\" integer not null default 0")
                .contains("\"sys_class_name\" varchar(64) not null default 'task'")
                .contains("\"short_description\" varchar(160)")
                .contains("\"active\" boolean");
    }

    @Test
    void childTableHasOnlyOwnColumnsAndFkPrimaryKey() {
        String sql = DdlGenerator.createChildTable("public", "incident", "task", List.of(
                new ColumnSpec("severity", FieldType.CHOICE, null, null)));

        assertThat(sql)
                .startsWith("create table \"public\".\"incident\"")
                .contains("\"sys_id\" uuid primary key references \"public\".\"task\"(\"sys_id\") on delete cascade")
                .contains("\"severity\" varchar(64)")
                // child tables must not duplicate base/audit columns
                .doesNotContain("sys_mod_count")
                .doesNotContain("sys_class_name")
                .doesNotContain("gen_random_uuid");
    }

    @Test
    void referenceColumnGetsForeignKey() {
        String sql = DdlGenerator.addColumn("public", "incident",
                new ColumnSpec("assigned_to", FieldType.REFERENCE, null, "sys_user"));

        assertThat(sql).isEqualTo(
                "alter table \"public\".\"incident\" add column \"assigned_to\" uuid "
                        + "references \"public\".\"sys_user\"(\"sys_id\") on delete set null");
    }

    @Test
    void stringWithoutMaxLengthDefaultsTo255() {
        String sql = DdlGenerator.addColumn("public", "task",
                new ColumnSpec("note", FieldType.STRING, null, null));
        assertThat(sql).contains("\"note\" varchar(255)");
    }

    @Test
    void typeMappingsMatchSpec() {
        assertThat(FieldType.STRING.pgType(40)).isEqualTo("varchar(40)");
        assertThat(FieldType.TEXT.pgType(null)).isEqualTo("text");
        assertThat(FieldType.INTEGER.pgType(null)).isEqualTo("bigint");
        assertThat(FieldType.DECIMAL.pgType(null)).isEqualTo("numeric(18,4)");
        assertThat(FieldType.BOOLEAN.pgType(null)).isEqualTo("boolean");
        assertThat(FieldType.DATETIME.pgType(null)).isEqualTo("timestamptz");
        assertThat(FieldType.CHOICE.pgType(null)).isEqualTo("varchar(64)");
        assertThat(FieldType.REFERENCE.pgType(null)).isEqualTo("uuid");
    }
}
