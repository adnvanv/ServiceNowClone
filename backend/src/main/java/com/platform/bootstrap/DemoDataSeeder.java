package com.platform.bootstrap;

import com.platform.core.ddl.SchemaChangeRequests.ChoiceRequest;
import com.platform.core.ddl.SchemaChangeRequests.CreateTableRequest;
import com.platform.core.ddl.SchemaChangeRequests.FieldRequest;
import com.platform.core.ddl.SchemaMutationService;
import com.platform.core.metadata.DictionaryService;
import com.platform.core.metadata.FieldType;
import com.platform.core.metadata.SchemaCache;
import com.platform.core.record.RecordService;
import com.platform.core.security.AclService;
import com.platform.core.security.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Seeds the demo on first startup. Everything a normal admin could do through
 * the API — creating the task/incident tables and inserting the demo records
 * — goes through the platform's own services (SchemaMutationService,
 * RecordService, AclService), which is the point: the seeder is proof the
 * platform works. Only the security principals themselves (users, groups,
 * roles) and the registration of sys_user as a referenceable table are direct
 * inserts, because v1 has no API surface for those.
 */
@Component
public class DemoDataSeeder {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final String SEED_ACTOR = "system";

    private final NamedParameterJdbcTemplate jdbc;
    private final DictionaryService dictionary;
    private final SchemaCache cache;
    private final SchemaMutationService schema;
    private final RecordService records;
    private final AclService acl;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;

    public DemoDataSeeder(NamedParameterJdbcTemplate jdbc, DictionaryService dictionary,
                          SchemaCache cache, SchemaMutationService schema, RecordService records,
                          AclService acl, PasswordEncoder passwordEncoder,
                          @Value("${platform.seed.demo-data}") boolean enabled) {
        this.jdbc = jdbc;
        this.dictionary = dictionary;
        this.cache = cache;
        this.schema = schema;
        this.records = records;
        this.acl = acl;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
    }

    /** Arbitrary key for the session-level advisory lock that serialises seeding across replicas. */
    static final long SEED_LOCK_KEY = 0x5EED_DA7AL;

    /**
     * Runs once per replica at startup. With several replicas starting at the
     * same moment (a Kubernetes Deployment with replicas: 3), the naive
     * "check whether task exists, then seed" is a race: every pod sees an
     * empty schema and they all seed. Flyway already serialises its
     * migrations with its own advisory lock, but this seeder runs after
     * Flyway and is application code, so it needs a lock of its own.
     *
     * A session-level pg_advisory_lock is held on one dedicated connection
     * for the whole seed (which spans many transactions, so the
     * transaction-scoped lock the schema service uses would not cover it).
     * The first pod seeds; the others block, then re-check under the lock,
     * find the data, and skip. If the seeding pod dies mid-way, Postgres
     * drops its session and releases the lock automatically.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        if (!enabled) {
            return;
        }
        jdbc.getJdbcTemplate().execute((ConnectionCallback<Void>) con -> {
            try (var st = con.prepareStatement("select pg_advisory_lock(?)")) {
                st.setLong(1, SEED_LOCK_KEY);
                st.execute();
            }
            try {
                seedIfEmpty();
            } finally {
                try (var st = con.prepareStatement("select pg_advisory_unlock(?)")) {
                    st.setLong(1, SEED_LOCK_KEY);
                    st.execute();
                }
            }
            return null;
        });
    }

    private void seedIfEmpty() {
        cache.reload();
        if (cache.table("task").isPresent()) {
            log.info("Demo data already present, skipping seed");
            return;
        }
        log.info("Seeding demo data...");
        UserContext system = UserContext.system();

        registerSysUser();

        // --- roles, users, groups -------------------------------------
        UUID adminRole = insertRole("admin", "Full platform access, bypasses ACLs");
        UUID itilRole = insertRole("itil", "IT service desk fulfiller");

        UUID admin = insertUser("admin", "admin", "System", "Administrator", "admin@example.com");
        UUID alice = insertUser("alice", "alice", "Alice", "Aguilar", "alice@example.com");
        UUID bob = insertUser("bob", "bob", "Bob", "Bennett", "bob@example.com");
        UUID carol = insertUser("carol", "carol", "Carol", "Chen", "carol@example.com");

        grantRoleToUser(admin, adminRole);
        // bob holds itil directly; alice gets it transitively via the group
        grantRoleToUser(bob, itilRole);
        UUID serviceDesk = insertGroup("Service Desk", "First-line incident handling");
        addGroupMember(serviceDesk, alice);
        grantRoleToGroup(serviceDesk, itilRole);

        // --- demo tables, created through the platform ----------------
        schema.createTable(new CreateTableRequest("task", "Task", null, true, List.of(
                new FieldRequest("number", "Number", "string", 40, null, false, null, false, null),
                new FieldRequest("short_description", "Short description", "string", 160, null,
                        true, null, true, null),
                new FieldRequest("description", "Description", "text", null, null, false, null,
                        false, null),
                new FieldRequest("state", "State", "choice", null, null, false, "1", false, List.of(
                        new ChoiceRequest("1", "New", 1),
                        new ChoiceRequest("2", "In Progress", 2),
                        new ChoiceRequest("3", "On Hold", 3),
                        new ChoiceRequest("6", "Resolved", 6),
                        new ChoiceRequest("7", "Closed", 7))),
                new FieldRequest("priority", "Priority", "choice", null, null, false, "3", false,
                        List.of(
                                new ChoiceRequest("1", "1 - Critical", 1),
                                new ChoiceRequest("2", "2 - High", 2),
                                new ChoiceRequest("3", "3 - Moderate", 3),
                                new ChoiceRequest("4", "4 - Low", 4))),
                new FieldRequest("assigned_to", "Assigned to", "reference", null, "sys_user",
                        false, null, false, null),
                new FieldRequest("active", "Active", "boolean", null, null, false, "true", false,
                        null))),
                system);

        schema.createTable(new CreateTableRequest("incident", "Incident", "task", false, List.of(
                new FieldRequest("category", "Category", "choice", null, null, false, null, false,
                        List.of(
                                new ChoiceRequest("network", "Network", 1),
                                new ChoiceRequest("hardware", "Hardware", 2),
                                new ChoiceRequest("software", "Software", 3),
                                new ChoiceRequest("database", "Database", 4),
                                new ChoiceRequest("security", "Security", 5))),
                new FieldRequest("severity", "Severity", "choice", null, null, false, "2", false,
                        List.of(
                                new ChoiceRequest("1", "1 - High", 1),
                                new ChoiceRequest("2", "2 - Medium", 2),
                                new ChoiceRequest("3", "3 - Low", 3))),
                new FieldRequest("resolution_notes", "Resolution notes", "text", null, null, false,
                        null, false, null))),
                system);

        // --- ACLs ------------------------------------------------------
        UUID taskId = cache.require("task").sysId();
        UUID incidentId = cache.require("incident").sysId();
        UUID sysUserId = cache.require("sys_user").sysId();

        // Fulfillers see and work every task/incident.
        acl.createAcl(taskId, null, "read", null, List.of(itilRole), SEED_ACTOR);
        acl.createAcl(incidentId, null, "read", null, List.of(itilRole), SEED_ACTOR);
        acl.createAcl(incidentId, null, "create", null, List.of(itilRole), SEED_ACTOR);
        acl.createAcl(incidentId, null, "write", null, List.of(itilRole), SEED_ACTOR);
        // Everyone else reads only incidents assigned to them: a role-less
        // rule whose condition resolves @me to the requesting user. This is
        // the row-filtering demo — carol sees a shorter list than alice.
        acl.createAcl(incidentId, null, "read", "assigned_to=@me", List.of(), SEED_ACTOR);
        // Nobody but admin deletes incidents (no delete rule = default deny).

        // Any authenticated user may read users (reference typeahead and
        // display values need this); only field-level rules expose columns.
        acl.createAcl(sysUserId, null, "read", null, List.of(), SEED_ACTOR);
        // Email is visible to fulfillers only — demonstrates a field-level
        // read ACL stripping a column for everyone else.
        acl.createAcl(sysUserId, "email", "read", null, List.of(itilRole), SEED_ACTOR);

        // --- ~50 incidents ---------------------------------------------
        seedIncidents(system, new UUID[]{alice, bob, carol, null});

        acl.reload();
        log.info("Demo data seeded: users admin/alice/bob/carol (password = username), "
                + "50 incidents on task/incident");
    }

    private void seedIncidents(UserContext system, UUID[] assignees) {
        String[] shortDescriptions = {
                "Email not syncing on mobile", "VPN drops every few minutes",
                "Laptop fan running loud", "Cannot print to 3rd floor printer",
                "Database connection pool exhausted", "Wifi dead in conference room B",
                "Password reset loop", "External monitor not detected",
                "Shared drive permission denied", "CRM search returns stale results",
                "Phishing email reported", "Build server disk full",
                "Slow login on kiosk machines", "Certificate expiring next week",
                "Two-factor prompt not arriving", "Video calls freezing",
                "Order export job failed overnight", "License server unreachable",
                "Keyboard input lag in terminal", "Backup job missed window"
        };
        String[] categories = {"network", "hardware", "software", "database", "security"};

        for (int i = 0; i < 50; i++) {
            Map<String, Object> values = new HashMap<>();
            values.put("number", String.format("INC%07d", 10001 + i));
            values.put("short_description", shortDescriptions[i % shortDescriptions.length]
                    + " (#" + (i + 1) + ")");
            values.put("description", "Reported by an end user. Investigation notes pending. "
                    + "This is demo record " + (i + 1) + ".");
            values.put("category", categories[i % categories.length]);
            values.put("priority", String.valueOf(1 + (i % 4)));
            values.put("severity", String.valueOf(1 + (i % 3)));
            values.put("state", String.valueOf(new int[]{1, 2, 3, 6, 7}[i % 5]));
            values.put("active", i % 5 < 3);
            UUID assignee = assignees[i % assignees.length];
            if (assignee != null) {
                values.put("assigned_to", assignee.toString());
            }
            if (i % 5 >= 3) {
                values.put("resolution_notes", "Resolved after triage; see change log.");
            }
            records.insert("incident", values, system);
        }
    }

    /**
     * sys_user exists physically (Flyway) but must be registered in the
     * metadata graph so user tables can reference it and the UI can render
     * it. password_hash is deliberately left unregistered: fields unknown to
     * sys_dictionary do not exist as far as the record API is concerned.
     */
    private void registerSysUser() {
        UUID tableId = dictionary.insertTable("sys_user", "User", null, false, SEED_ACTOR);
        dictionary.insertField(tableId, "user_name", "User name", FieldType.STRING, 100, null,
                true, null, true, SEED_ACTOR);
        dictionary.insertField(tableId, "first_name", "First name", FieldType.STRING, 100, null,
                false, null, false, SEED_ACTOR);
        dictionary.insertField(tableId, "last_name", "Last name", FieldType.STRING, 100, null,
                false, null, false, SEED_ACTOR);
        dictionary.insertField(tableId, "email", "Email", FieldType.STRING, 200, null,
                false, null, false, SEED_ACTOR);
        dictionary.insertField(tableId, "active", "Active", FieldType.BOOLEAN, null, null,
                false, "true", false, SEED_ACTOR);
        cache.reload();
    }

    // --- direct inserts for security principals (no v1 API for these) ---

    private UUID insertUser(String userName, String password, String first, String last,
                            String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into sys_user (sys_id, user_name, password_hash, first_name, last_name,
                                      email, active, sys_created_by, sys_updated_by)
                values (:id, :u, :hash, :first, :last, :email, true, :actor, :actor)
                """,
                Map.of("id", id, "u", userName, "hash", passwordEncoder.encode(password),
                        "first", first, "last", last, "email", email, "actor", SEED_ACTOR));
        return id;
    }

    private UUID insertRole(String name, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into sys_role (sys_id, name, description, sys_created_by, sys_updated_by)
                values (:id, :name, :description, :actor, :actor)
                """,
                Map.of("id", id, "name", name, "description", description, "actor", SEED_ACTOR));
        return id;
    }

    private UUID insertGroup(String name, String description) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into sys_user_group (sys_id, name, description, sys_created_by, sys_updated_by)
                values (:id, :name, :description, :actor, :actor)
                """,
                Map.of("id", id, "name", name, "description", description, "actor", SEED_ACTOR));
        return id;
    }

    private void grantRoleToUser(UUID userId, UUID roleId) {
        jdbc.update("insert into sys_user_has_role (user_id, role_id) values (:u, :r)",
                Map.of("u", userId, "r", roleId));
    }

    private void grantRoleToGroup(UUID groupId, UUID roleId) {
        jdbc.update("insert into sys_group_has_role (group_id, role_id) values (:g, :r)",
                Map.of("g", groupId, "r", roleId));
    }

    private void addGroupMember(UUID groupId, UUID userId) {
        jdbc.update("insert into sys_group_member (group_id, user_id) values (:g, :u)",
                Map.of("g", groupId, "u", userId));
    }
}
