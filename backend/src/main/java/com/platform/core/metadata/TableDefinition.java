package com.platform.core.metadata;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One row of sys_db_object: a table known to the platform. {@code name} is
 * also the physical Postgres table name. {@code fields} holds only the fields
 * declared directly on this table — inherited fields are resolved through
 * {@link SchemaCache#fieldsInChain}.
 */
public record TableDefinition(
        UUID sysId,
        String name,
        String label,
        UUID superClassId,
        boolean extendable,
        List<FieldDefinition> fields) {

    public boolean isRoot() {
        return superClassId == null;
    }

    public Optional<FieldDefinition> ownField(String element) {
        return fields.stream().filter(f -> f.element().equals(element)).findFirst();
    }
}
