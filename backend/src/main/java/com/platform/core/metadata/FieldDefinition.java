package com.platform.core.metadata;

import java.util.UUID;

/**
 * One row of sys_dictionary: a single field on a single table.
 * {@code element} is the physical column name.
 */
public record FieldDefinition(
        UUID sysId,
        UUID tableId,
        String element,
        String label,
        FieldType type,
        Integer maxLength,
        UUID referenceTableId,
        boolean mandatory,
        String defaultValue,
        boolean display) {
}
