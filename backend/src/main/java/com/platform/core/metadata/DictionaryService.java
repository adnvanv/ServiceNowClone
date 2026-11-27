package com.platform.core.metadata;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The metadata repository: all reads and writes of sys_db_object,
 * sys_dictionary and sys_choice go through here. Writes are only ever invoked
 * from SchemaMutationService, which wraps them in the same transaction as the
 * physical DDL.
 */
@Service
public class DictionaryService {

    private final NamedParameterJdbcTemplate jdbc;

    public DictionaryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The committed schema version — one primary-key lookup, cheap enough to poll. */
    public long schemaVersion() {
        Long v = jdbc.getJdbcTemplate().queryForObject(
                "select version from sys_schema_version where id = 1", Long.class);
        return v != null ? v : 0L;
    }

    /**
     * Bump the schema version. Must be called inside the schema-mutation
     * transaction so other replicas see the new version only once the DDL and
     * metadata it describes have committed.
     */
    public void bumpSchemaVersion() {
        jdbc.getJdbcTemplate().update(
                "update sys_schema_version set version = version + 1 where id = 1");
    }

    /** Load the whole metadata graph in three queries. Called only on reload. */
    public SchemaSnapshot loadSnapshot() {
        List<FieldDefinition> allFields = jdbc.query("""
                select sys_id, table_id, element, label, internal_type, max_length,
                       reference_table, mandatory, default_value, display
                from sys_dictionary
                order by sys_created_on
                """,
                (rs, i) -> new FieldDefinition(
                        rs.getObject("sys_id", UUID.class),
                        rs.getObject("table_id", UUID.class),
                        rs.getString("element"),
                        rs.getString("label"),
                        FieldType.fromInternal(rs.getString("internal_type")),
                        rs.getObject("max_length", Integer.class),
                        rs.getObject("reference_table", UUID.class),
                        rs.getBoolean("mandatory"),
                        rs.getString("default_value"),
                        rs.getBoolean("display")));

        Map<UUID, List<FieldDefinition>> fieldsByTable = new HashMap<>();
        for (FieldDefinition f : allFields) {
            fieldsByTable.computeIfAbsent(f.tableId(), k -> new ArrayList<>()).add(f);
        }

        Map<String, TableDefinition> byName = new LinkedHashMap<>();
        Map<UUID, TableDefinition> byId = new HashMap<>();
        jdbc.query("""
                select sys_id, name, label, super_class, is_extendable
                from sys_db_object
                order by name
                """,
                rs -> {
                    UUID id = rs.getObject("sys_id", UUID.class);
                    TableDefinition t = new TableDefinition(
                            id,
                            rs.getString("name"),
                            rs.getString("label"),
                            rs.getObject("super_class", UUID.class),
                            rs.getBoolean("is_extendable"),
                            List.copyOf(fieldsByTable.getOrDefault(id, List.of())));
                    byName.put(t.name(), t);
                    byId.put(id, t);
                });

        Map<UUID, Map<String, List<ChoiceDefinition>>> choices = new HashMap<>();
        jdbc.query("""
                select table_id, element, value, label, sequence
                from sys_choice
                order by table_id, element, sequence, value
                """,
                rs -> {
                    ChoiceDefinition c = new ChoiceDefinition(
                            rs.getObject("table_id", UUID.class),
                            rs.getString("element"),
                            rs.getString("value"),
                            rs.getString("label"),
                            rs.getInt("sequence"));
                    choices.computeIfAbsent(c.tableId(), k -> new HashMap<>())
                            .computeIfAbsent(c.element(), k -> new ArrayList<>())
                            .add(c);
                });

        return new SchemaSnapshot(Map.copyOf(byName), Map.copyOf(byId), deepCopy(choices));
    }

    private static Map<UUID, Map<String, List<ChoiceDefinition>>> deepCopy(
            Map<UUID, Map<String, List<ChoiceDefinition>>> src) {
        Map<UUID, Map<String, List<ChoiceDefinition>>> out = new HashMap<>();
        src.forEach((table, byElement) -> {
            Map<String, List<ChoiceDefinition>> inner = new HashMap<>();
            byElement.forEach((el, list) -> inner.put(el, List.copyOf(list)));
            out.put(table, Map.copyOf(inner));
        });
        return Map.copyOf(out);
    }

    /**
     * Direct DB lookup, bypassing the cache. Used inside schema-mutation
     * transactions, where the advisory lock is held and the cache may not yet
     * reflect a concurrent (committed) change.
     */
    public java.util.Optional<TableDefinition> findTableByName(String name) {
        List<TableDefinition> found = jdbc.query("""
                select sys_id, name, label, super_class, is_extendable
                from sys_db_object where name = :name
                """,
                Map.of("name", name),
                (rs, i) -> new TableDefinition(
                        rs.getObject("sys_id", UUID.class),
                        rs.getString("name"),
                        rs.getString("label"),
                        rs.getObject("super_class", UUID.class),
                        rs.getBoolean("is_extendable"),
                        List.of()));
        return found.stream().findFirst();
    }

    public UUID insertTable(String name, String label, UUID superClassId, boolean extendable, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into sys_db_object (sys_id, name, label, super_class, is_extendable,
                                           sys_created_by, sys_updated_by)
                values (:id, :name, :label, :superClass, :extendable, :actor, :actor)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", name)
                        .addValue("label", label)
                        .addValue("superClass", superClassId)
                        .addValue("extendable", extendable)
                        .addValue("actor", actor));
        return id;
    }

    public UUID insertField(UUID tableId, String element, String label, FieldType type,
                            Integer maxLength, UUID referenceTableId, boolean mandatory,
                            String defaultValue, boolean display, String actor) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into sys_dictionary (sys_id, table_id, element, label, internal_type,
                                            max_length, reference_table, mandatory,
                                            default_value, display,
                                            sys_created_by, sys_updated_by)
                values (:id, :tableId, :element, :label, :type, :maxLength, :refTable,
                        :mandatory, :defaultValue, :display, :actor, :actor)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("tableId", tableId)
                        .addValue("element", element)
                        .addValue("label", label)
                        .addValue("type", type.internalType())
                        .addValue("maxLength", maxLength)
                        .addValue("refTable", referenceTableId)
                        .addValue("mandatory", mandatory)
                        .addValue("defaultValue", defaultValue)
                        .addValue("display", display)
                        .addValue("actor", actor));
        return id;
    }

    public void insertChoice(UUID tableId, String element, String value, String label,
                             int sequence, String actor) {
        jdbc.update("""
                insert into sys_choice (table_id, element, value, label, sequence,
                                        sys_created_by, sys_updated_by)
                values (:tableId, :element, :value, :label, :sequence, :actor, :actor)
                """,
                new MapSqlParameterSource()
                        .addValue("tableId", tableId)
                        .addValue("element", element)
                        .addValue("value", value)
                        .addValue("label", label)
                        .addValue("sequence", sequence)
                        .addValue("actor", actor));
    }
}
