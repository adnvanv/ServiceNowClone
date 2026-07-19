package com.platform.core.metadata;

import java.util.UUID;

/** One row of sys_choice: a dropdown option for a choice field. */
public record ChoiceDefinition(
        UUID tableId,
        String element,
        String value,
        String label,
        int sequence) {
}
