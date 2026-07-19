package com.platform.core.ddl;

import java.util.List;

/**
 * Input shapes for schema mutations. These double as the JSON request bodies
 * of the meta API, so they use raw strings (type names, table names) that
 * SchemaMutationService validates and resolves.
 */
public final class SchemaChangeRequests {

    public record CreateTableRequest(
            String name,
            String label,
            String extendsTable,
            Boolean extendable,
            List<FieldRequest> fields) {
    }

    public record FieldRequest(
            String element,
            String label,
            String type,
            Integer maxLength,
            String referenceTable,
            Boolean mandatory,
            String defaultValue,
            Boolean display,
            List<ChoiceRequest> choices) {
    }

    public record ChoiceRequest(String value, String label, Integer sequence) {
    }

    private SchemaChangeRequests() {
    }
}
