package com.platform.core.metadata;

import com.platform.core.error.NotFoundException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory cache of the full metadata graph.
 *
 * Normal record reads and writes never touch the sys_* tables — they resolve
 * everything (tables, fields, choices, inheritance chains) from here. The
 * cache is reloaded wholesale after every schema mutation; a version counter
 * lets callers detect staleness cheaply.
 *
 * Reload-the-world instead of patching the cache incrementally is a
 * deliberate choice: schema changes are rare and administrative, the graph is
 * small, and an atomic snapshot swap cannot leave readers with a partially
 * updated view.
 */
@Component
public class SchemaCache {

    private final DictionaryService dictionary;
    private final AtomicLong version = new AtomicLong();
    private volatile SchemaSnapshot snapshot = SchemaSnapshot.EMPTY;
    private volatile boolean loaded = false;

    public SchemaCache(DictionaryService dictionary) {
        this.dictionary = dictionary;
    }

    /** Swap in a fresh snapshot. Called after every committed schema change. */
    public synchronized void reload() {
        snapshot = dictionary.loadSnapshot();
        loaded = true;
        version.incrementAndGet();
    }

    private SchemaSnapshot snap() {
        if (!loaded) {
            synchronized (this) {
                if (!loaded) {
                    reload();
                }
            }
        }
        return snapshot;
    }

    public long version() {
        return version.get();
    }

    public Optional<TableDefinition> table(String name) {
        return Optional.ofNullable(snap().byName().get(name));
    }

    public TableDefinition require(String name) {
        return table(name).orElseThrow(() -> new NotFoundException("Unknown table: " + name));
    }

    public TableDefinition byId(UUID id) {
        TableDefinition t = snap().byId().get(id);
        if (t == null) {
            throw new NotFoundException("Unknown table id: " + id);
        }
        return t;
    }

    public List<TableDefinition> allTables() {
        return List.copyOf(snap().byName().values());
    }

    /** The inheritance chain of {@code table}, root first, ending with {@code table} itself. */
    public List<TableDefinition> chain(TableDefinition table) {
        List<TableDefinition> chain = new ArrayList<>();
        TableDefinition current = table;
        while (current != null) {
            chain.add(0, current);
            current = current.superClassId() != null ? snap().byId().get(current.superClassId()) : null;
        }
        return chain;
    }

    public TableDefinition root(TableDefinition table) {
        return chain(table).get(0);
    }

    /** All dictionary fields visible on {@code table}: its own plus everything inherited, root first. */
    public List<FieldDefinition> fieldsInChain(TableDefinition table) {
        List<FieldDefinition> fields = new ArrayList<>();
        for (TableDefinition t : chain(table)) {
            fields.addAll(t.fields());
        }
        return fields;
    }

    /**
     * Resolve a field by element name anywhere in {@code table}'s chain.
     * Base columns are not dictionary rows, so they are not found here —
     * callers that accept base columns check {@link BaseColumns} first.
     */
    public Optional<FieldDefinition> field(TableDefinition table, String element) {
        for (TableDefinition t : chain(table)) {
            Optional<FieldDefinition> f = t.ownField(element);
            if (f.isPresent()) {
                return f;
            }
        }
        return Optional.empty();
    }

    /** The table in the chain that physically owns {@code element}, for split writes. */
    public Optional<TableDefinition> owningTable(TableDefinition table, String element) {
        for (TableDefinition t : chain(table)) {
            if (t.ownField(element).isPresent()) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Choice options for a field, looked up on the table in the chain that declares it. */
    public List<ChoiceDefinition> choices(TableDefinition table, String element) {
        for (TableDefinition t : chain(table)) {
            if (t.ownField(element).isPresent()) {
                return snap().choices()
                        .getOrDefault(t.sysId(), java.util.Map.of())
                        .getOrDefault(element, List.of());
            }
        }
        return List.of();
    }

    /** Direct children of {@code table}. */
    public List<TableDefinition> childrenOf(TableDefinition table) {
        return snap().byName().values().stream()
                .filter(t -> table.sysId().equals(t.superClassId()))
                .toList();
    }
}
