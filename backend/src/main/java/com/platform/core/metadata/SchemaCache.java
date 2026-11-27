package com.platform.core.metadata;

import com.platform.core.error.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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
 *
 * Multi-replica coherence: each pod has its own cache, so a change made
 * through pod A must reach pods B and C. Every schema mutation bumps
 * sys_schema_version inside its DDL transaction; each replica polls that one
 * row (default every second) and reloads when it moves. On top of that, a
 * cache miss in {@link #require} re-checks the version before answering 404,
 * so "create a table on one pod, immediately query it on another" works
 * without waiting for the next poll.
 *
 * Alternatives rejected: Postgres LISTEN/NOTIFY needs a dedicated long-lived
 * connection per pod and still has to fall back to polling after a
 * reconnect; Redis pub/sub or a message bus adds infrastructure for what is a
 * rare, administrative event.
 */
@Component
public class SchemaCache {

    private final DictionaryService dictionary;
    private static final Logger log = LoggerFactory.getLogger(SchemaCache.class);

    private final AtomicLong version = new AtomicLong();
    private volatile SchemaSnapshot snapshot = SchemaSnapshot.EMPTY;
    private volatile boolean loaded = false;
    /** The committed sys_schema_version the current snapshot was loaded at. */
    private volatile long dbVersion = -1;

    public SchemaCache(DictionaryService dictionary) {
        this.dictionary = dictionary;
    }

    /** Swap in a fresh snapshot. Called after every committed schema change. */
    public synchronized void reload() {
        // Read the version *before* the snapshot: if another replica commits
        // in between, our snapshot may already contain the change, but the
        // recorded version is older, so the next poll reloads again. Reading
        // it after could record a version newer than the snapshot and miss a
        // change forever.
        long v = dictionary.schemaVersion();
        snapshot = dictionary.loadSnapshot();
        dbVersion = v;
        loaded = true;
        version.incrementAndGet();
    }

    /** Reload if another replica has committed a schema change. Returns true if it reloaded. */
    public boolean refreshIfStale() {
        if (!loaded) {
            return false; // nothing cached yet; the first read loads lazily
        }
        long committed = dictionary.schemaVersion();
        if (committed != dbVersion) {
            synchronized (this) {
                if (committed != dbVersion) {
                    log.debug("Schema version {} -> {}, reloading cache", dbVersion, committed);
                    reload();
                    return true;
                }
            }
        }
        return false;
    }

    @Scheduled(fixedDelayString = "${platform.schema.poll-interval-ms:1000}")
    void pollForChanges() {
        try {
            refreshIfStale();
        } catch (RuntimeException e) {
            // A DB blip must not kill the scheduler thread; the next tick retries.
            log.warn("Schema version poll failed: {}", e.getMessage());
        }
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
        Optional<TableDefinition> t = table(name);
        if (t.isEmpty() && refreshIfStale()) {
            // Possibly created moments ago on another replica.
            t = table(name);
        }
        return t.orElseThrow(() -> new NotFoundException("Unknown table: " + name));
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
