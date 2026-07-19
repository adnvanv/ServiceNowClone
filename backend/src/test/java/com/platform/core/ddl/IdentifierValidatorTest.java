package com.platform.core.ddl;

import com.platform.core.error.BadRequestException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IdentifierValidatorTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "incident", "a", "task2", "short_description", "priority_2",
            "x123456789012345678901234567890123456789012345678901234567", // 59 chars
            // Unreserved Postgres keywords must be allowed — they are common
            // business field names and are safe because identifiers are quoted.
            "state", "number", "name", "value", "type", "label", "active", "sequence"
    })
    void acceptsValidIdentifiers(String id) {
        assertThat(IdentifierValidator.validate(id)).isEqualTo(id);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {
            " ", "Incident", "INCIDENT", "1abc", "_abc", "foo-bar", "foo bar",
            "foo.bar", "tablé", "таблица", "foo\nbar",
            "x123456789012345678901234567890123456789012345678901234567890", // > 59 chars
    })
    void rejectsMalformedIdentifiers(String id) {
        assertThatThrownBy(() -> IdentifierValidator.validate(id))
                .isInstanceOf(BadRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"select", "table", "user", "where", "order", "group",
            "from", "join", "create", "grant", "union", "true", "false", "null"})
    void rejectsReservedWords(String id) {
        assertThatThrownBy(() -> IdentifierValidator.validate(id))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"sys_id", "sys_user", "sys_anything", "sys_"})
    void rejectsPlatformNamespace(String id) {
        assertThatThrownBy(() -> IdentifierValidator.validate(id))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(id);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "foo\"; drop table sys_user; --",
            "foo\".\"bar",
            "foo'; delete from sys_user; --",
            "foo); drop table sys_user; --",
            "foo/*comment*/bar",
            "foo--comment",
            "foo;bar",
            "\"; drop table \"incident",
    })
    void rejectsInjectionPayloads(String id) {
        assertThatThrownBy(() -> IdentifierValidator.validate(id))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void quoteWrapsInDoubleQuotes() {
        assertThat(IdentifierValidator.quote("incident")).isEqualTo("\"incident\"");
    }

    @Test
    void quoteRefusesEmbeddedQuotes() {
        assertThatThrownBy(() -> IdentifierValidator.quote("a\"b"))
                .isInstanceOf(BadRequestException.class);
    }
}
