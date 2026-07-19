package com.platform.core.metadata;

import com.platform.core.error.BadRequestException;

/**
 * The platform's field type system. Each logical type maps to exactly one
 * Postgres column type; the mapping lives here so DDL generation and value
 * coercion agree on it.
 */
public enum FieldType {

    STRING("string"),
    TEXT("text"),
    INTEGER("integer"),
    DECIMAL("decimal"),
    BOOLEAN("boolean"),
    DATETIME("datetime"),
    CHOICE("choice"),
    REFERENCE("reference");

    private final String internalType;

    FieldType(String internalType) {
        this.internalType = internalType;
    }

    public String internalType() {
        return internalType;
    }

    public static FieldType fromInternal(String internalType) {
        for (FieldType t : values()) {
            if (t.internalType.equals(internalType)) {
                return t;
            }
        }
        throw new BadRequestException("Unknown field type: " + internalType);
    }

    /**
     * The Postgres column type for this field type. {@code maxLength} only
     * matters for STRING; everything else has a fixed physical type.
     */
    public String pgType(Integer maxLength) {
        return switch (this) {
            case STRING -> "varchar(" + (maxLength != null ? maxLength : 255) + ")";
            case TEXT -> "text";
            case INTEGER -> "bigint";
            case DECIMAL -> "numeric(18,4)";
            case BOOLEAN -> "boolean";
            case DATETIME -> "timestamptz";
            // Choice values are stored as their raw string value; labels live in sys_choice.
            case CHOICE -> "varchar(64)";
            // References store the target record's sys_id; the FK constraint is added by DdlGenerator.
            case REFERENCE -> "uuid";
        };
    }
}
