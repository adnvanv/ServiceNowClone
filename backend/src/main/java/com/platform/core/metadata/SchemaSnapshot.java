package com.platform.core.metadata;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An immutable view of the entire metadata graph at one point in time.
 * Readers grab the current snapshot once and work against it, so a schema
 * change mid-request can never hand them a half-updated graph.
 *
 * choices is keyed by owning table id, then element.
 */
public record SchemaSnapshot(
        Map<String, TableDefinition> byName,
        Map<UUID, TableDefinition> byId,
        Map<UUID, Map<String, List<ChoiceDefinition>>> choices) {

    public static final SchemaSnapshot EMPTY = new SchemaSnapshot(Map.of(), Map.of(), Map.of());
}
