package com.platform.core.security;

import com.platform.core.metadata.DictionaryService;
import com.platform.core.metadata.FieldDefinition;
import com.platform.core.metadata.FieldType;
import com.platform.core.metadata.SchemaCache;
import com.platform.core.metadata.SchemaSnapshot;
import com.platform.core.metadata.TableDefinition;
import com.platform.core.query.EncodedQueryParser;
import com.platform.core.query.QueryMatcher;
import com.platform.core.query.QueryNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure evaluation tests on a synthetic schema: task (root, extendable) and
 * incident extends task.
 */
class AclEvaluatorTest {

    private static final UUID TASK_ID = UUID.randomUUID();
    private static final UUID INCIDENT_ID = UUID.randomUUID();

    private TableDefinition task;
    private TableDefinition incident;
    private AclEvaluator evaluator;

    private final UserContext admin = new UserContext(UUID.randomUUID(), "admin", Set.of("admin"));
    private final UserContext itil = new UserContext(UUID.randomUUID(), "itil_user", Set.of("itil"));
    private final UserContext plain = new UserContext(UUID.randomUUID(), "plain_user", Set.of());

    @BeforeEach
    void setUp() {
        task = new TableDefinition(TASK_ID, "task", "Task", null, true, List.of(
                field(TASK_ID, "active", FieldType.BOOLEAN),
                field(TASK_ID, "priority", FieldType.INTEGER),
                field(TASK_ID, "assigned_to", FieldType.REFERENCE)));
        incident = new TableDefinition(INCIDENT_ID, "incident", "Incident", TASK_ID, false, List.of(
                field(INCIDENT_ID, "severity", FieldType.CHOICE)));

        SchemaSnapshot snapshot = new SchemaSnapshot(
                Map.of("task", task, "incident", incident),
                Map.of(TASK_ID, task, INCIDENT_ID, incident),
                Map.of());
        DictionaryService dictionary = mock(DictionaryService.class);
        when(dictionary.loadSnapshot()).thenReturn(snapshot);
        SchemaCache cache = new SchemaCache(dictionary);
        evaluator = new AclEvaluator(cache, new QueryMatcher(cache));
    }

    private static FieldDefinition field(UUID tableId, String element, FieldType type) {
        return new FieldDefinition(UUID.randomUUID(), tableId, element, element, type,
                null, null, false, null, false);
    }

    private static AclRule rule(UUID tableId, String field, String op, String condition,
                                String... roles) {
        QueryNode cond = condition != null ? EncodedQueryParser.parse(condition) : null;
        return new AclRule(UUID.randomUUID(), tableId, field, op, cond, Set.of(roles), true);
    }

    private static Map<UUID, List<AclRule>> rules(AclRule... rules) {
        Map<UUID, List<AclRule>> byTable = new HashMap<>();
        for (AclRule r : rules) {
            byTable.computeIfAbsent(r.tableId(), k -> new java.util.ArrayList<>()).add(r);
        }
        return byTable;
    }

    @Nested
    class DefaultPostureAndAdmin {

        @Test
        void noRulesMeansDenyForNonAdmin() {
            assertThat(evaluator.allowsOperation(rules(), incident, "read", plain, null)).isFalse();
            assertThat(evaluator.readDecision(rules(), incident, plain))
                    .isInstanceOf(AclDecision.Deny.class);
        }

        @Test
        void adminBypassesEverything() {
            // Even a rule the admin's roles don't match is bypassed.
            Map<UUID, List<AclRule>> r = rules(rule(INCIDENT_ID, null, "read", null, "itil"));
            UserContext adminWithoutItil = admin;
            assertThat(evaluator.allowsOperation(r, incident, "read", adminWithoutItil, null)).isTrue();
            assertThat(evaluator.allowsOperation(rules(), incident, "delete", admin, null)).isTrue();
            assertThat(evaluator.readDecision(rules(), incident, admin))
                    .isInstanceOf(AclDecision.Allow.class);
        }
    }

    @Nested
    class RoleMatching {

        @Test
        void userWithListedRolePasses() {
            Map<UUID, List<AclRule>> r = rules(rule(INCIDENT_ID, null, "write", null, "itil"));
            assertThat(evaluator.allowsOperation(r, incident, "write", itil, null)).isTrue();
            assertThat(evaluator.allowsOperation(r, incident, "write", plain, null)).isFalse();
        }

        @Test
        void ruleWithNoRolesAppliesToAnyUser() {
            Map<UUID, List<AclRule>> r = rules(rule(INCIDENT_ID, null, "read", null));
            assertThat(evaluator.allowsOperation(r, incident, "read", plain, null)).isTrue();
        }

        @Test
        void anyPassingRuleAtTheLevelGrantsAccess() {
            Map<UUID, List<AclRule>> r = rules(
                    rule(INCIDENT_ID, null, "write", null, "admin_only_role"),
                    rule(INCIDENT_ID, null, "write", null, "itil"));
            assertThat(evaluator.allowsOperation(r, incident, "write", itil, null)).isTrue();
        }
    }

    @Nested
    class ConditionMatching {

        @Test
        void conditionGatesOnRecordValues() {
            Map<UUID, List<AclRule>> r = rules(
                    rule(INCIDENT_ID, null, "write", "active=true", "itil"));
            assertThat(evaluator.allowsOperation(r, incident, "write", itil,
                    Map.of("active", true))).isTrue();
            assertThat(evaluator.allowsOperation(r, incident, "write", itil,
                    Map.of("active", false))).isFalse();
        }

        @Test
        void conditionRulesPassWhenNoRecordIsAvailable() {
            // UI capability hints evaluate without a record; conditions are
            // then optimistic — enforcement happens again at mutation time.
            Map<UUID, List<AclRule>> r = rules(
                    rule(INCIDENT_ID, null, "write", "active=true", "itil"));
            assertThat(evaluator.allowsOperation(r, incident, "write", itil, null)).isTrue();
        }
    }

    @Nested
    class InheritanceResolution {

        @Test
        void parentRuleAppliesWhenChildHasNone() {
            Map<UUID, List<AclRule>> r = rules(rule(TASK_ID, null, "read", null, "itil"));
            assertThat(evaluator.allowsOperation(r, incident, "read", itil, null)).isTrue();
            assertThat(evaluator.allowsOperation(r, incident, "read", plain, null)).isFalse();
        }

        @Test
        void childRuleOverridesParentRule() {
            // Parent allows everyone, child restricts to itil: on incident,
            // the child rule is the most specific match and wins outright.
            Map<UUID, List<AclRule>> r = rules(
                    rule(TASK_ID, null, "read", null),
                    rule(INCIDENT_ID, null, "read", null, "itil"));
            assertThat(evaluator.allowsOperation(r, incident, "read", plain, null)).isFalse();
            assertThat(evaluator.allowsOperation(r, incident, "read", itil, null)).isTrue();
            // On task itself the parent rule still applies to everyone.
            assertThat(evaluator.allowsOperation(r, task, "read", plain, null)).isTrue();
        }

        @Test
        void fieldRuleOnExactTableBeatsTableRule() {
            Map<UUID, List<AclRule>> r = rules(
                    rule(INCIDENT_ID, null, "write", null),           // table: anyone
                    rule(INCIDENT_ID, "severity", "write", null, "itil")); // field: itil only
            assertThat(evaluator.allowsField(r, incident, "write", "severity", plain, null)).isFalse();
            assertThat(evaluator.allowsField(r, incident, "write", "severity", itil, null)).isTrue();
            // Other fields fall back to the table-level rule.
            assertThat(evaluator.allowsField(r, incident, "write", "priority", plain, null)).isTrue();
        }

        @Test
        void fieldRuleOnParentAppliesToChild() {
            Map<UUID, List<AclRule>> r = rules(
                    rule(TASK_ID, "priority", "write", null, "itil"));
            assertThat(evaluator.allowsField(r, incident, "write", "priority", plain, null)).isFalse();
            assertThat(evaluator.allowsField(r, incident, "write", "priority", itil, null)).isTrue();
        }
    }

    @Nested
    class ReadDecisions {

        @Test
        void unconditionalRoleMatchIsAllow() {
            Map<UUID, List<AclRule>> r = rules(rule(INCIDENT_ID, null, "read", null, "itil"));
            assertThat(evaluator.readDecision(r, incident, itil))
                    .isInstanceOf(AclDecision.Allow.class);
        }

        @Test
        void roleMismatchIsDeny() {
            Map<UUID, List<AclRule>> r = rules(rule(INCIDENT_ID, null, "read", null, "itil"));
            assertThat(evaluator.readDecision(r, incident, plain))
                    .isInstanceOf(AclDecision.Deny.class);
        }

        @Test
        void conditionalGrantRestrictsWithItsCondition() {
            Map<UUID, List<AclRule>> r = rules(
                    rule(INCIDENT_ID, null, "read", "assigned_to=" + plain.userId()));
            AclDecision d = evaluator.readDecision(r, incident, plain);
            assertThat(d).isInstanceOf(AclDecision.Restrict.class);
            QueryNode cond = ((AclDecision.Restrict) d).condition();
            assertThat(cond).isInstanceOf(QueryNode.Condition.class);
        }

        @Test
        void multipleConditionalGrantsAreOrEd() {
            Map<UUID, List<AclRule>> r = rules(
                    rule(INCIDENT_ID, null, "read", "active=true"),
                    rule(INCIDENT_ID, null, "read", "priority=1"));
            AclDecision d = evaluator.readDecision(r, incident, plain);
            assertThat(d).isInstanceOf(AclDecision.Restrict.class);
            assertThat(((AclDecision.Restrict) d).condition()).isInstanceOf(QueryNode.Or.class);
        }

        @Test
        void unconditionalGrantWinsOverConditionalAtSameLevel() {
            Map<UUID, List<AclRule>> r = rules(
                    rule(INCIDENT_ID, null, "read", "active=true"),
                    rule(INCIDENT_ID, null, "read", null, "itil"));
            assertThat(evaluator.readDecision(r, incident, itil))
                    .isInstanceOf(AclDecision.Allow.class);
        }
    }
}
