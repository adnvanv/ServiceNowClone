package com.platform;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests over HTTP against a real Postgres. The demo seed runs
 * first (same as production startup), providing users, roles and the
 * task/incident hierarchy; tests create additional tables of their own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformIntegrationTest {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate rawJdbc;

    private String adminToken;
    private String aliceToken;
    private String carolToken;

    @BeforeAll
    void logins() {
        adminToken = login("admin", "admin");
        aliceToken = login("alice", "alice");
        carolToken = login("carol", "carol");
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() {
            };

    private String login(String user, String password) {
        ResponseEntity<Map<String, Object>> response = rest.exchange("/api/now/auth/login",
                HttpMethod.POST, new HttpEntity<>(Map.of("username", user, "password", password)),
                MAP);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return (String) result(response).get("token");
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            h.setBearerAuth(token);
        }
        return h;
    }

    private ResponseEntity<Map<String, Object>> call(HttpMethod method, String url, String token,
                                                     Object body) {
        return rest.exchange(url, method, new HttpEntity<>(body, headers(token)), MAP);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> result(ResponseEntity<Map<String, Object>> response) {
        return (Map<String, Object>) response.getBody().get("result");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultList(
            ResponseEntity<Map<String, Object>> response) {
        return (List<Map<String, Object>>) response.getBody().get("result");
    }

    // ------------------------------------------------------------------

    @Nested
    class AuthAndErrors {

        @Test
        void unauthenticatedRequestsGet401() {
            assertThat(call(HttpMethod.GET, "/api/now/table/incident", null, null).getStatusCode())
                    .isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void badPasswordGets401() {
            ResponseEntity<Map<String, Object>> r = rest.exchange("/api/now/auth/login",
                    HttpMethod.POST,
                    new HttpEntity<>(Map.of("username", "admin", "password", "nope")), MAP);
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }

        @Test
        void unknownTableGets404() {
            assertThat(call(HttpMethod.GET, "/api/now/table/nope", adminToken, null)
                    .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void unknownFieldInQueryGets400() {
            assertThat(call(HttpMethod.GET,
                    "/api/now/table/incident?sysparm_query=not_a_field=1", adminToken, null)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void malformedQueryGets400() {
            assertThat(call(HttpMethod.GET,
                    "/api/now/table/incident?sysparm_query=priority=", adminToken, null)
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
    }

    @Nested
    class SchemaLifecycle {

        @Test
        void createTableAddFieldInsertAndQuery() {
            // create a fresh table through the API
            ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
                    "/api/now/meta/table", adminToken,
                    Map.of("name", "asset", "label", "Asset", "extendable", true,
                            "fields", List.of(
                                    Map.of("element", "model", "label", "Model", "type", "string",
                                            "mandatory", true, "display", true),
                                    Map.of("element", "cost", "label", "Cost", "type", "decimal"))));
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            // physical table really exists
            Integer physical = rawJdbc.queryForObject("""
                    select count(*) from information_schema.tables
                    where table_schema = 'public' and table_name = 'asset'
                    """, Integer.class);
            assertThat(physical).isEqualTo(1);

            // add a field afterwards → physical column appears
            assertThat(call(HttpMethod.POST, "/api/now/meta/table/asset/field", adminToken,
                    Map.of("element", "serial", "label", "Serial", "type", "string"))
                    .getStatusCode()).isEqualTo(HttpStatus.CREATED);
            Integer column = rawJdbc.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_schema = 'public' and table_name = 'asset'
                      and column_name = 'serial'
                    """, Integer.class);
            assertThat(column).isEqualTo(1);

            // insert + encoded query round-trip
            ResponseEntity<Map<String, Object>> inserted = call(HttpMethod.POST,
                    "/api/now/table/asset", adminToken,
                    Map.of("model", "ThinkPad X1", "cost", 1899.50, "serial", "SN-001"));
            assertThat(inserted.getStatusCode()).isEqualTo(HttpStatus.CREATED);

            ResponseEntity<Map<String, Object>> queried = call(HttpMethod.GET,
                    "/api/now/table/asset?sysparm_query=modelLIKEthinkpad^cost>1000",
                    adminToken, null);
            assertThat(queried.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resultList(queried)).hasSize(1);
            assertThat(resultList(queried).get(0).get("serial")).isEqualTo("SN-001");
            assertThat(queried.getHeaders().getFirst("X-Total-Count")).isEqualTo("1");
        }

        @Test
        void invalidIdentifiersAreRejected() {
            assertThat(call(HttpMethod.POST, "/api/now/meta/table", adminToken,
                    Map.of("name", "foo\"; drop table sys_user; --", "label", "x"))
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(call(HttpMethod.POST, "/api/now/meta/table", adminToken,
                    Map.of("name", "sys_hack", "label", "x"))
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(call(HttpMethod.POST, "/api/now/meta/table", adminToken,
                    Map.of("name", "select", "label", "x"))
                    .getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void nonAdminCannotChangeSchema() {
            assertThat(call(HttpMethod.POST, "/api/now/meta/table", aliceToken,
                    Map.of("name", "alice_table", "label", "x"))
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }
    }

    @Nested
    class Inheritance {

        @Test
        void writeSplitsAcrossChainAndParentQuerySeesChildRows() {
            // laptop extends the seeded, extendable task table? No — use a
            // dedicated hierarchy to keep the seed data untouched.
            call(HttpMethod.POST, "/api/now/meta/table", adminToken,
                    Map.of("name", "vehicle", "label", "Vehicle", "extendable", true,
                            "fields", List.of(
                                    Map.of("element", "vin", "label", "VIN", "type", "string",
                                            "display", true))));
            call(HttpMethod.POST, "/api/now/meta/table", adminToken,
                    Map.of("name", "truck", "label", "Truck", "extendsTable", "vehicle",
                            "fields", List.of(
                                    Map.of("element", "payload_kg", "label", "Payload (kg)",
                                            "type", "integer"))));

            ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
                    "/api/now/table/truck", adminToken,
                    Map.of("vin", "TRK-42", "payload_kg", 3500));
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            String sysId = (String) result(created).get("sys_id").toString();

            // the write split one logical record across both physical tables
            Integer inVehicle = rawJdbc.queryForObject(
                    "select count(*) from vehicle where sys_id = ?::uuid and vin = 'TRK-42'",
                    Integer.class, sysId);
            Integer inTruck = rawJdbc.queryForObject(
                    "select count(*) from truck where sys_id = ?::uuid and payload_kg = 3500",
                    Integer.class, sysId);
            assertThat(inVehicle).isEqualTo(1);
            assertThat(inTruck).isEqualTo(1);
            // and the child table did NOT get its own vin column
            Integer vinOnTruck = rawJdbc.queryForObject("""
                    select count(*) from information_schema.columns
                    where table_name = 'truck' and column_name = 'vin'
                    """, Integer.class);
            assertThat(vinOnTruck).isZero();

            // reading the child joins the chain: both fields come back
            ResponseEntity<Map<String, Object>> get = call(HttpMethod.GET,
                    "/api/now/table/truck/" + sysId, adminToken, null);
            assertThat(result(get).get("vin")).isEqualTo("TRK-42");
            assertThat(result(get).get("payload_kg")).isEqualTo(3500);

            // querying the parent returns the row with its actual class
            ResponseEntity<Map<String, Object>> parents = call(HttpMethod.GET,
                    "/api/now/table/vehicle?sysparm_query=vin=TRK-42", adminToken, null);
            assertThat(resultList(parents)).hasSize(1);
            assertThat(resultList(parents).get(0).get("sys_class_name")).isEqualTo("truck");

            // deleting via the child removes the root row too (cascade chain)
            call(HttpMethod.DELETE, "/api/now/table/truck/" + sysId, adminToken, null);
            Integer remaining = rawJdbc.queryForObject(
                    "select count(*) from vehicle where sys_id = ?::uuid", Integer.class, sysId);
            assertThat(remaining).isZero();
        }

        @Test
        void seededIncidentInheritsTaskFieldsAndDotWalks() {
            ResponseEntity<Map<String, Object>> incidents = call(HttpMethod.GET,
                    "/api/now/table/incident?sysparm_query=assigned_to.user_name=alice"
                            + "&sysparm_fields=number,short_description,assigned_to.user_name"
                            + "&sysparm_limit=5",
                    adminToken, null);
            assertThat(incidents.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(resultList(incidents)).isNotEmpty();
            assertThat(resultList(incidents).get(0).get("assigned_to.user_name"))
                    .isEqualTo("alice");
        }
    }

    @Nested
    class AccessControl {

        @Test
        void conditionAclFiltersRowsPerUser() {
            // alice (itil) sees all 50 seeded incidents
            ResponseEntity<Map<String, Object>> aliceList = call(HttpMethod.GET,
                    "/api/now/table/incident?sysparm_limit=100", aliceToken, null);
            assertThat(resultList(aliceList).size()).isGreaterThanOrEqualTo(50);

            // carol (no roles) sees only incidents assigned to her
            ResponseEntity<Map<String, Object>> carolList = call(HttpMethod.GET,
                    "/api/now/table/incident?sysparm_limit=100", carolToken, null);
            List<Map<String, Object>> carolRows = resultList(carolList);
            assertThat(carolRows).isNotEmpty();
            assertThat(carolRows.size()).isLessThan(resultList(aliceList).size());
            // the count header reflects her filtered view, not the table size
            assertThat(Integer.parseInt(carolList.getHeaders().getFirst("X-Total-Count")))
                    .isEqualTo(carolRows.size());

            // a record not assigned to carol is a 404 for her
            String foreignId = resultList(aliceList).stream()
                    .filter(r -> !carolRows.stream().map(c -> c.get("sys_id"))
                            .toList().contains(r.get("sys_id")))
                    .findFirst().orElseThrow().get("sys_id").toString();
            assertThat(call(HttpMethod.GET, "/api/now/table/incident/" + foreignId,
                    carolToken, null).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void writeAclBlocksNonFulfillers() {
            String anyIncident = resultList(call(HttpMethod.GET,
                    "/api/now/table/incident?sysparm_limit=1", carolToken, null))
                    .get(0).get("sys_id").toString();
            // carol may read (it is hers) but not write
            assertThat(call(HttpMethod.PATCH, "/api/now/table/incident/" + anyIncident,
                    carolToken, Map.of("short_description", "carol was here"))
                    .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            // alice (itil) may write
            assertThat(call(HttpMethod.PATCH, "/api/now/table/incident/" + anyIncident,
                    aliceToken, Map.of("short_description", "updated by alice"))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            // nobody but admin deletes (default deny)
            assertThat(call(HttpMethod.DELETE, "/api/now/table/incident/" + anyIncident,
                    aliceToken, null).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        }

        @Test
        void fieldLevelAclStripsColumn() {
            // email on sys_user is readable by itil only
            ResponseEntity<Map<String, Object>> asAlice = call(HttpMethod.GET,
                    "/api/now/table/sys_user?sysparm_query=user_name=bob", aliceToken, null);
            assertThat(resultList(asAlice).get(0)).containsKey("email");

            ResponseEntity<Map<String, Object>> asCarol = call(HttpMethod.GET,
                    "/api/now/table/sys_user?sysparm_query=user_name=bob", carolToken, null);
            assertThat(resultList(asCarol).get(0)).doesNotContainKey("email");
        }

        @Test
        void displayValuesResolveReferencesAndChoices() {
            ResponseEntity<Map<String, Object>> list = call(HttpMethod.GET,
                    "/api/now/table/incident?sysparm_display_value=true&sysparm_limit=1"
                            + "&sysparm_query=assigned_to.user_name=alice^state=1",
                    aliceToken, null);
            Map<String, Object> row = resultList(list).get(0);
            assertThat(row.get("state")).isInstanceOf(Map.class);
            assertThat(((Map<?, ?>) row.get("state")).get("display_value")).isEqualTo("New");
            assertThat(((Map<?, ?>) row.get("assigned_to")).get("display_value"))
                    .isEqualTo("alice");
        }
    }

    @Nested
    class OptimisticLocking {

        @Test
        void staleModCountGets409() {
            ResponseEntity<Map<String, Object>> created = call(HttpMethod.POST,
                    "/api/now/table/incident", adminToken,
                    Map.of("number", "INC9999901", "short_description", "lock test"));
            String sysId = result(created).get("sys_id").toString();

            // first update with the correct mod count succeeds
            assertThat(call(HttpMethod.PATCH, "/api/now/table/incident/" + sysId, adminToken,
                    Map.of("short_description", "first", "sys_mod_count", 0))
                    .getStatusCode()).isEqualTo(HttpStatus.OK);
            // replaying the same expected mod count now conflicts
            assertThat(call(HttpMethod.PATCH, "/api/now/table/incident/" + sysId, adminToken,
                    Map.of("short_description", "second", "sys_mod_count", 0))
                    .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
    }

    @Nested
    class Concurrency {

        @Test
        void simultaneousCreateOfSameTableLeavesConsistentState() throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch start = new CountDownLatch(1);
                List<Future<HttpStatus>> results = List.of(
                        pool.submit(() -> raceCreate(start)),
                        pool.submit(() -> raceCreate(start)));
                start.countDown();
                List<HttpStatus> statuses = List.of(results.get(0).get(), results.get(1).get());

                // exactly one create wins; the loser gets a clean conflict,
                // not a broken half-created table
                assertThat(statuses).containsExactlyInAnyOrder(
                        HttpStatus.CREATED, HttpStatus.CONFLICT);
                Integer metaRows = rawJdbc.queryForObject(
                        "select count(*) from sys_db_object where name = 'race_table'",
                        Integer.class);
                assertThat(metaRows).isEqualTo(1);
                Integer physical = rawJdbc.queryForObject("""
                        select count(*) from information_schema.tables
                        where table_schema = 'public' and table_name = 'race_table'
                        """, Integer.class);
                assertThat(physical).isEqualTo(1);
                // and the surviving table is fully usable
                assertThat(call(HttpMethod.POST, "/api/now/table/race_table", adminToken,
                        Map.of("winner", "yes")).getStatusCode()).isEqualTo(HttpStatus.CREATED);
            } finally {
                pool.shutdownNow();
            }
        }

        private HttpStatus raceCreate(CountDownLatch start) throws InterruptedException {
            start.await();
            return (HttpStatus) call(HttpMethod.POST, "/api/now/meta/table", adminToken,
                    Map.of("name", "race_table", "label", "Race",
                            "fields", List.of(Map.of("element", "winner", "label", "Winner",
                                    "type", "string"))))
                    .getStatusCode();
        }
    }
}
