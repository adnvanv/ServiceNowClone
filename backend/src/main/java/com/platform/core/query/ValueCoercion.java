package com.platform.core.query;

import com.platform.core.error.BadRequestException;
import com.platform.core.metadata.FieldType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Converts raw string values from encoded queries and JSON payloads into the
 * Java types the JDBC driver should bind. Centralised so SqlBuilder (WHERE
 * parameters), RecordService (insert/update values) and QueryMatcher (in-
 * memory ACL conditions) can never disagree about what "true" or a date means.
 */
public final class ValueCoercion {

    private static final DateTimeFormatter SPACE_DATETIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Coerce a raw string to the bind value for a field of the given type. */
    public static Object coerce(FieldType type, String raw, String fieldForError) {
        if (raw == null) {
            return null;
        }
        try {
            return switch (type) {
                case STRING, TEXT, CHOICE -> raw;
                case INTEGER -> Long.parseLong(raw.trim());
                case DECIMAL -> new BigDecimal(raw.trim());
                case BOOLEAN -> parseBoolean(raw.trim(), fieldForError);
                case DATETIME -> parseDateTime(raw.trim());
                case REFERENCE -> UUID.fromString(raw.trim());
            };
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new BadRequestException(
                    "Invalid value for field '" + fieldForError + "'",
                    "'" + raw + "' cannot be read as " + type.internalType());
        } catch (IllegalArgumentException e) {
            throw new BadRequestException(
                    "Invalid value for field '" + fieldForError + "'",
                    "'" + raw + "' is not a valid " + type.internalType());
        }
    }

    private static Boolean parseBoolean(String raw, String field) {
        return switch (raw.toLowerCase()) {
            case "true", "1" -> Boolean.TRUE;
            case "false", "0" -> Boolean.FALSE;
            default -> throw new BadRequestException(
                    "Invalid value for field '" + field + "'",
                    "'" + raw + "' is not a boolean");
        };
    }

    /** Accepts ISO offset datetimes, plain "yyyy-MM-dd HH:mm:ss" and dates (interpreted as UTC). */
    private static OffsetDateTime parseDateTime(String raw) {
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(raw, SPACE_DATETIME).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        try {
            return LocalDateTime.parse(raw).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ignored) {
            // fall through
        }
        return LocalDate.parse(raw).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** The logical type of a base column, for coercion and WHERE compilation. */
    public static FieldType baseColumnType(String element) {
        return switch (element) {
            case "sys_id" -> FieldType.REFERENCE;
            case "sys_created_on", "sys_updated_on" -> FieldType.DATETIME;
            case "sys_mod_count" -> FieldType.INTEGER;
            default -> FieldType.STRING; // sys_created_by, sys_updated_by, sys_class_name
        };
    }

    private ValueCoercion() {
    }
}
