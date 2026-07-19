package com.platform.core.query;

import com.platform.core.error.BadRequestException;
import com.platform.core.query.QueryNode.And;
import com.platform.core.query.QueryNode.Condition;
import com.platform.core.query.QueryNode.MatchAll;
import com.platform.core.query.QueryNode.Or;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EncodedQueryParserTest {

    private static Condition cond(QueryNode n) {
        return (Condition) n;
    }

    @Nested
    class SingleConditions {

        @ParameterizedTest
        @CsvSource(delimiterString = "|", value = {
                "active=true          | active   | EQUALS       | true",
                "priority!=3          | priority | NOT_EQUALS   | 3",
                "priority>2           | priority | GREATER_THAN | 2",
                "priority<2           | priority | LESS_THAN    | 2",
                "priority>=2          | priority | GTE          | 2",
                "priority<=2          | priority | LTE          | 2",
                "nameLIKEnet          | name     | LIKE         | net",
                "nameSTARTSWITHab     | name     | STARTS_WITH  | ab",
                "nameENDSWITHxy       | name     | ENDS_WITH    | xy",
                "priorityIN1,2,3      | priority | IN           | '1,2,3'",
                "priorityNOT IN1,2    | priority | NOT_IN       | '1,2'",
                "created_onBETWEEN2024-01-01@2024-12-31 | created_on | BETWEEN | 2024-01-01@2024-12-31",
        })
        void parsesEveryBinaryOperator(String encoded, String field, Operator op, String value) {
            Condition c = cond(EncodedQueryParser.parse(encoded));
            assertThat(c.field()).isEqualTo(field);
            assertThat(c.operator()).isEqualTo(op);
            assertThat(c.rawValue()).isEqualTo(value);
        }

        @Test
        void parsesUnaryOperators() {
            Condition empty = cond(EncodedQueryParser.parse("assigned_toISEMPTY"));
            assertThat(empty.operator()).isEqualTo(Operator.IS_EMPTY);
            assertThat(empty.rawValue()).isEmpty();

            Condition notEmpty = cond(EncodedQueryParser.parse("assigned_toISNOTEMPTY"));
            assertThat(notEmpty.operator()).isEqualTo(Operator.IS_NOT_EMPTY);
        }

        @Test
        void valueMayContainOperatorCharacters() {
            Condition c = cond(EncodedQueryParser.parse("description=a=b>c"));
            assertThat(c.rawValue()).isEqualTo("a=b>c");
        }

        @Test
        void longestOperatorWins() {
            // ">=" must not lex as ">" with value "=2"
            Condition c = cond(EncodedQueryParser.parse("priority>=2"));
            assertThat(c.operator()).isEqualTo(Operator.GTE);
            // "ISNOTEMPTY" must not lex as "IN" inside it
            Condition c2 = cond(EncodedQueryParser.parse("stateISNOTEMPTY"));
            assertThat(c2.operator()).isEqualTo(Operator.IS_NOT_EMPTY);
        }

        @Test
        void dotWalkedFieldParses() {
            Condition c = cond(EncodedQueryParser.parse("assigned_to.department=it"));
            assertThat(c.field()).isEqualTo("assigned_to.department");
            assertThat(c.rawValue()).isEqualTo("it");
        }
    }

    @Nested
    class Combinations {

        @Test
        void caretMeansAnd() {
            And and = (And) EncodedQueryParser.parse("active=true^priority=1");
            assertThat(and.children()).hasSize(2);
            assertThat(cond(and.children().get(0)).field()).isEqualTo("active");
            assertThat(cond(and.children().get(1)).field()).isEqualTo("priority");
        }

        @Test
        void orBindsToPrecedingConditionOnly() {
            // active=true AND (priority=1 OR priority=2)
            And and = (And) EncodedQueryParser.parse("active=true^priority=1^ORpriority=2");
            assertThat(and.children()).hasSize(2);
            assertThat(cond(and.children().get(0)).field()).isEqualTo("active");
            Or or = (Or) and.children().get(1);
            assertThat(or.children()).hasSize(2);
            assertThat(cond(or.children().get(0)).rawValue()).isEqualTo("1");
            assertThat(cond(or.children().get(1)).rawValue()).isEqualTo("2");
        }

        @Test
        void andContinuesAfterOrGroup() {
            // a AND (b OR c) AND d
            And and = (And) EncodedQueryParser.parse("a=1^b=2^ORc=3^d=4");
            assertThat(and.children()).hasSize(3);
            assertThat(cond(and.children().get(0)).field()).isEqualTo("a");
            assertThat(((Or) and.children().get(1)).children()).hasSize(2);
            assertThat(cond(and.children().get(2)).field()).isEqualTo("d");
        }

        @Test
        void chainedOrsFormOneGroup() {
            Or or = (Or) EncodedQueryParser.parse("p=1^ORp=2^ORp=3");
            assertThat(or.children()).hasSize(3);
        }

        @Test
        void newQueryIsTopLevelOr() {
            // (priority IN 1,2,3) OR (state=7)
            Or top = (Or) EncodedQueryParser.parse("priorityIN1,2,3^NQstate=7");
            assertThat(top.children()).hasSize(2);
            assertThat(cond(top.children().get(0)).operator()).isEqualTo(Operator.IN);
            assertThat(cond(top.children().get(1)).field()).isEqualTo("state");
        }

        @Test
        void newQueryGroupsKeepTheirInternalStructure() {
            // (a=1 AND b=2) OR (c=3 AND (d=4 OR d=5))
            Or top = (Or) EncodedQueryParser.parse("a=1^b=2^NQc=3^d=4^ORd=5");
            assertThat(top.children()).hasSize(2);
            And left = (And) top.children().get(0);
            assertThat(left.children()).hasSize(2);
            And right = (And) top.children().get(1);
            assertThat(right.children()).hasSize(2);
            assertThat(right.children().get(1)).isInstanceOf(Or.class);
        }

        @Test
        void briefExampleParses() {
            And and = (And) EncodedQueryParser.parse("short_descriptionLIKEnetwork^assigned_toISEMPTY");
            assertThat(cond(and.children().get(0)).operator()).isEqualTo(Operator.LIKE);
            assertThat(cond(and.children().get(1)).operator()).isEqualTo(Operator.IS_EMPTY);
        }
    }

    @Nested
    class EmptyAndMalformed {

        @Test
        void nullAndBlankParseToMatchAll() {
            assertThat(EncodedQueryParser.parse(null)).isInstanceOf(MatchAll.class);
            assertThat(EncodedQueryParser.parse("")).isInstanceOf(MatchAll.class);
            assertThat(EncodedQueryParser.parse("   ")).isInstanceOf(MatchAll.class);
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "active=true^",          // trailing connector
                "^active=true",          // leading connector
                "a=1^^b=2",              // empty segment
                "=true",                 // missing field
                "active",                // missing operator
                "activetrue",            // no operator at all
                "priorityISEMPTY1",      // unary operator with value
                "priority=",             // binary operator without value
                "priority>",             // comparison without value
                "^ORactive=true",        // OR with nothing before it
                "Active=true",           // uppercase field: not a valid field char
        })
        void malformedQueriesAreRejected(String encoded) {
            assertThatThrownBy(() -> EncodedQueryParser.parse(encoded))
                    .isInstanceOf(BadRequestException.class);
        }

        @Test
        void nqWithEmptySecondGroupIsRejected() {
            assertThatThrownBy(() -> EncodedQueryParser.parse("a=1^NQ"))
                    .isInstanceOf(BadRequestException.class);
        }
    }
}
