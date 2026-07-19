# Build brief — open-source metadata-driven application platform (v1)

## Mission

Build the core of a metadata-driven application platform, in the spirit of ServiceNow's Glide engine.

The defining characteristic: **a user defines a table and its fields at runtime, and immediately gets a working REST API, a list view, a form view, and enforced access control — with zero code written for that specific table.** No hand-written entity classes, no hand-written controllers, no hand-written UI per table.

If someone can create a table called `incident`, add a `priority` field, and immediately query it over REST and edit it in the UI, v1 is a success.

This is v1 of a larger system. Later phases (workflow engine, multi-tenancy, AI agents) are listed at the end — do not build them now, but do not make architectural choices that block them.

---

## Stack

- **Java 21**, **Spring Boot 3.x**
- **PostgreSQL 16**
- **Flyway** — for the platform's own bootstrap schema only (the `sys_*` metadata tables). User-created tables are made at runtime via DDL, not migrations.
- **Spring JDBC (`NamedParameterJdbcTemplate`)** for all data access.
  - **Do not use JPA/Hibernate.** This is deliberate and important: the schema is not known at compile time, so an ORM built around compile-time entity mappings is the wrong tool. All SQL is generated dynamically. Note this reasoning in an ADR.
- **React 18 + TypeScript + Vite** for the frontend
- **JUnit 5 + Testcontainers** for testing (real Postgres in tests, not H2 — runtime DDL behaviour is Postgres-specific)
- **Docker Compose** so the whole thing starts with one command

---

## Architecture overview

Two layers:

1. **Metadata layer** — `sys_*` tables that describe what tables and fields exist. Created by Flyway at startup. These are ordinary tables with a fixed schema.
2. **Runtime layer** — everything else is generated from that metadata: physical user tables, the REST API, the UI, and ACL enforcement.

Package structure under `com.platform`:

```
core/metadata   TableDefinition, FieldDefinition, DictionaryService, SchemaCache
core/ddl        IdentifierValidator, DdlGenerator, SchemaMutationService
core/query      EncodedQueryParser, QueryNode (AST), SqlBuilder, RecordQuery
core/record     RecordService, Record, InheritanceResolver
core/security   UserContext, AclService, AclEvaluator
api             TableApiController, MetaApiController, AuthController
bootstrap       DemoDataSeeder
```

---

## 1. Metadata tables (Flyway migration `V1__bootstrap.sql`)

All use `uuid` primary keys named `sys_id`, plus `sys_created_on`, `sys_created_by`, `sys_updated_on`, `sys_updated_by`.

**`sys_db_object`** — one row per table
| column | type | notes |
|---|---|---|
| `sys_id` | uuid | PK |
| `name` | text unique | internal name, e.g. `incident`. Also the physical table name. |
| `label` | text | display label |
| `super_class` | uuid null | FK to `sys_db_object` — the table this one extends |
| `is_extendable` | boolean | |

**`sys_dictionary`** — one row per field
| column | type | notes |
|---|---|---|
| `sys_id` | uuid | PK |
| `table_id` | uuid | FK to `sys_db_object` |
| `element` | text | column name, e.g. `priority` |
| `label` | text | |
| `internal_type` | text | see field types below |
| `max_length` | int null | for strings |
| `reference_table` | uuid null | FK to `sys_db_object`, required when type is `reference` |
| `mandatory` | boolean | |
| `default_value` | text null | |
| `display` | boolean | is this the field shown when this record is referenced elsewhere |
| unique | | `(table_id, element)` |

**`sys_choice`** — dropdown options: `table_id`, `element`, `value`, `label`, `sequence`

**Security tables:** `sys_user` (with `user_name` unique, `password_hash` bcrypt, `first_name`, `last_name`, `email`, `active`), `sys_user_group`, `sys_group_member`, `sys_role` (`name` unique), `sys_user_has_role`, `sys_group_has_role`.

**`sys_security_acl`** — access control rules
| column | type | notes |
|---|---|---|
| `sys_id` | uuid | PK |
| `table_id` | uuid | FK to `sys_db_object` |
| `field` | text null | null = table-level rule; otherwise field-level |
| `operation` | text | `create` / `read` / `write` / `delete` |
| `condition` | text null | an encoded query evaluated against the record |
| `active` | boolean | |

**`sys_acl_role`** — join table: `acl_id`, `role_id`. An ACL passes if the user holds **any** listed role (or the ACL lists no roles) **and** the condition matches.

### Field types (`internal_type`)

`string`, `text` (long), `integer`, `decimal`, `boolean`, `datetime`, `choice`, `reference`.

Map to Postgres as: `varchar(n)`, `text`, `bigint`, `numeric(18,4)`, `boolean`, `timestamptz`, `varchar(64)`, `uuid` (with a real FK constraint to the referenced table's `sys_id`).

---

## 2. Runtime DDL — the storage model

When a table is created through the platform, **physically create it in Postgres**. When a field is added, **physically add the column**. This mirrors what ServiceNow actually does and gives real types, real indexes and real constraints.

### Base columns

Every user-created table gets, automatically:

```
sys_id          uuid primary key default gen_random_uuid()
sys_created_on  timestamptz not null default now()
sys_created_by  text
sys_updated_on  timestamptz not null default now()
sys_updated_by  text
sys_mod_count   integer not null default 0
```

`sys_mod_count` increments on every update and is used for optimistic locking — a `PATCH` may optionally supply the expected value and gets a `409` on mismatch.

### Inheritance model

Use **class-table (joined) inheritance**, as ServiceNow does.

- `task` gets a physical table `task` with its own columns.
- `incident extends task` gets a physical table `incident` containing **only incident-specific columns**, plus `sys_id uuid primary key references task(sys_id) on delete cascade`.
- Reading `incident` performs an `INNER JOIN` up the whole chain to the root.
- Writing an `incident` record splits the field values by owning table and inserts into each table in the chain, root first, **inside a single transaction**, reusing the same `sys_id`.
- Querying the parent table `task` returns rows from all descendants. Include a computed `sys_class_name` so callers know the actual class of each row. Resolve it either with a stored `sys_class_name` column on the root table (simpler, recommended) or by left-joining children — pick the stored column and document why.

Document the tradeoff in an ADR: joined inheritance keeps storage clean and avoids sparse tables, at the cost of a JOIN per level of depth. This is a well-known performance characteristic of deeply extended ServiceNow tables.

### Identifier safety — critical

Table and field names come from user input and end up in SQL identifiers, which **cannot be parameterised**. Therefore:

- Validate every identifier against `^[a-z][a-z0-9_]{0,58}$` before it goes anywhere near SQL. Reject on failure with a clear error.
- Maintain a reject-list of Postgres reserved words and of names beginning with `sys_` (reserved for the platform).
- Always emit identifiers double-quoted in generated SQL.
- Put this in a single `IdentifierValidator` class that **every** DDL path calls. Unit-test it hard, including injection attempts like `foo"; drop table sys_user; --`.
- Field **values** are always bound as prepared-statement parameters. Never interpolated. No exceptions.

### Atomicity and concurrency

- Postgres has transactional DDL — use it. Inserting the `sys_db_object` / `sys_dictionary` rows and running the `CREATE TABLE` / `ALTER TABLE` happen in **one transaction**. If either fails, both roll back. Call this out in the README; it is a genuine advantage of Postgres here.
- Serialise schema changes with a Postgres advisory lock so two concurrent "create table" requests cannot interleave.
- Maintain an in-memory `SchemaCache` of the full metadata graph, with a version counter bumped on every schema change. Never hit the metadata tables on the hot path of a normal record read.

---

## 3. Query layer

### Encoded query language

Implement a parser for ServiceNow-style encoded queries. This is a centrepiece — write it as a proper tokenizer + recursive-descent parser producing an AST, not a pile of regexes.

Grammar:
- `^` — AND
- `^OR` — OR (binds to the preceding condition)
- `^NQ` — new query (top-level OR between groups)
- Operators: `=`, `!=`, `>`, `<`, `>=`, `<=`, `LIKE`, `STARTSWITH`, `ENDSWITH`, `IN`, `NOT IN`, `ISEMPTY`, `ISNOTEMPTY`, `BETWEEN`

Examples:
```
active=true^priority=1
active=true^priority=1^ORpriority=2
short_descriptionLIKEnetwork^assigned_toISEMPTY
priorityIN1,2,3^NQstate=7
```

Compile the AST to a parameterised `WHERE` clause. Validate every referenced field against the schema cache and reject unknown fields with a `400`.

### RecordQuery API

A fluent, `GlideRecord`-flavoured Java API used by everything else in the system:

```java
List<Record> results = RecordQuery.of("incident")
    .addQuery("active", true)
    .addQuery("priority", Operator.LTE, 2)
    .orderByDesc("sys_created_on")
    .limit(20)
    .fetch(userContext);
```

`fetch` always takes a `UserContext` so ACLs cannot be bypassed by accident.

### Dot-walking (v1 scope: read-only, one level)

Support `assigned_to.department` in field selection and in query conditions, resolved as a JOIN through the reference field. One level deep is enough for v1; design the resolver so deeper walking is a later extension rather than a rewrite.

---

## 4. Access control

**ACLs are enforced in the query layer, not in controllers.** A controller must not be able to forget them.

- **Read**: the ACL's condition is compiled and injected into the `WHERE` clause, so denied rows are never fetched. Field-level read denials strip those fields from the returned record.
- **Create / write / delete**: evaluated before the mutation; on failure return `403` with which operation and which field failed.
- **Resolution order**: walk from the most specific match to the least — field-level rule on the exact table, then table-level on the exact table, then inherited rules up the `super_class` chain. Most specific match wins.
- A user holding the `admin` role bypasses ACL evaluation.
- Default posture when no ACL exists for a table: **deny**, except for `admin`. Seed sensible ACLs for the demo tables so this is not annoying in practice.

Auth: username/password login issuing a JWT; BCrypt password hashing; Spring Security filter populating `UserContext` (user, roles resolved transitively through groups) per request.

---

## 5. REST API

Mirror ServiceNow's Table API shape closely — it is a good design and it makes the resemblance legible.

```
GET    /api/now/table/{table}
GET    /api/now/table/{table}/{sys_id}
POST   /api/now/table/{table}
PATCH  /api/now/table/{table}/{sys_id}
DELETE /api/now/table/{table}/{sys_id}
```

Query parameters: `sysparm_query` (encoded query), `sysparm_fields` (comma-separated projection), `sysparm_limit`, `sysparm_offset`, `sysparm_order_by`, `sysparm_display_value` (`true` returns resolved display values for reference and choice fields instead of raw ids).

Metadata / admin endpoints:
```
GET    /api/now/meta/tables                 list all tables
GET    /api/now/meta/table/{table}          full field definitions, inheritance chain, choices — this is what the UI renders from
POST   /api/now/meta/table                  create a table (triggers DDL)
POST   /api/now/meta/table/{table}/field    add a field (triggers DDL)
```

Consistent response envelope: `{ "result": ... }` on success, `{ "error": { "message", "detail" } }` on failure. Correct status codes: `400` bad query, `401` unauthenticated, `403` ACL denial, `404` unknown table or record, `409` optimistic-lock conflict.

Include a total-count header on list responses for pagination.

---

## 6. Frontend

**Hard rule: no per-table components.** Everything renders from `GET /api/now/meta/table/{table}`. If you find yourself writing an `IncidentForm.tsx`, the architecture has failed.

Screens:

1. **Login**
2. **Navigator** — sidebar listing all tables the user can read
3. **List view** — columns from the dictionary, sortable headers, pagination, and a filter builder that composes an encoded query string (show the raw encoded query in an editable box too; it is a nice touch and useful for debugging)
4. **Form view** — fields rendered by `internal_type`:
   - `string` → text input; `text` → textarea; `integer`/`decimal` → number input
   - `boolean` → checkbox; `datetime` → datetime picker
   - `choice` → select populated from `sys_choice`
   - `reference` → typeahead picker searching the referenced table's display field
   - mandatory fields marked and validated client-side, re-validated server-side
   - fields hidden or read-only per field-level ACLs returned by the meta endpoint
5. **Schema admin** — create a table (name, label, optional parent to extend), add fields, manage choices. This is the screen that makes the demo land.

Keep the styling clean and restrained. A dense, professional list/form layout reads far better here than anything decorative.

---

## 7. Demo seed data

`DemoDataSeeder` runs on first startup and builds the demo **through the platform's own APIs**, not raw SQL — this proves the platform actually works:

- `task` table: `number`, `short_description`, `description`, `state` (choice), `priority` (choice), `assigned_to` (reference to `sys_user`), `active`
- `incident extends task`: adds `category` (choice), `severity` (choice), `resolution_notes`
- Roles `admin` and `itil`; a handful of users; groups
- ACLs: `itil` can read/write incidents; a non-`itil` user can read only incidents assigned to them (demonstrates a condition-based read ACL genuinely filtering rows)
- ~50 incident records with varied data so list filtering and sorting are visibly working

---

## 8. Testing

Not exhaustive coverage — targeted tests on the parts where correctness is subtle:

- **`EncodedQueryParser`** — the biggest unit test suite. Every operator, nesting, `^OR` binding, `^NQ`, malformed input, unknown fields.
- **`IdentifierValidator`** — valid names, invalid names, reserved words, injection payloads.
- **`AclEvaluator`** — role matching, condition matching, inheritance resolution order, admin bypass, field-level denial.
- **Integration (Testcontainers)** — create a table via API → add fields → insert a record → query it with an encoded query → verify inheritance write splits across both physical tables → verify an ACL actually blocks a denied user.
- **Concurrency** — two simultaneous create-table calls do not corrupt state.

---

## 9. Deliverables

- Runs with `docker compose up` and nothing else. Seeded and usable immediately.
- **`README.md`** — what it is, the one-paragraph pitch, screenshots, quickstart, API examples with `curl`.
- **`ARCHITECTURE.md`** — how metadata drives the runtime, the request lifecycle from HTTP through ACL to SQL, the inheritance model, the schema cache. Include a Mermaid diagram of the metadata layer and a Mermaid ERD of the `sys_*` tables.
- **`docs/adr/`** — short architecture decision records for: (1) runtime DDL vs JSONB vs EAV, (2) joined-table inheritance vs single-table, (3) no ORM, (4) ACL enforcement at the query layer.
- **Code comments on the non-obvious parts** — the query compiler, the inheritance resolver, the DDL transaction handling. Explain *why*, not *what*.

> Write the code to be read and understood by someone who will have to explain every design decision out loud in a technical interview. Favour clarity over cleverness. Where you make a judgement call, leave a comment saying what the alternative was and why you rejected it.

---

## 10. Explicit non-goals for v1

Do not build these now. They are later phases and building them now will compromise the core.

- Workflow engine, business rules, scheduled jobs
- Multi-tenancy or instance provisioning
- AI or LLM features of any kind
- Update sets / change tracking
- Email, notifications, SLAs, reporting, dashboards
- A UI page builder
- Import sets and transform maps

---

## 11. Future phases — build v1 so these drop in cleanly

Keep these in view. They are the reason for the architecture above; do not implement them yet, but avoid choices that block them.

**Phase 2 — business rules and the script engine.** Before/after insert/update/delete hooks stored as records in `sys_script`, attached to a table and a condition, executing in an engine-managed context. Requires: a clean interception point in `RecordService` for every mutation, and a `RecordContext` object carrying current, previous, and user state. *v1 implication:* route every write through one choke point in `RecordService`. Never let a controller build SQL directly.

**Phase 3 — the workflow engine.** Workflows defined as data (a graph of typed steps), executed by a durable state machine with a persistent job queue, retries with backoff, idempotent step execution, and per-step audit records. This is the eventual centrepiece. *v1 implication:* the metadata pattern must generalise beyond tables — workflows will also be records defining behaviour. Do not hard-code anything that assumes metadata only ever describes schema.

**Phase 4 — multi-tenancy and instance provisioning.** Schema-per-tenant isolation, subdomain routing, and provisioning implemented as a workflow executed by the Phase 3 engine (the platform provisioning itself), plus instance clone. *v1 implication (important — this is the expensive one to retrofit):* thread a `TenantContext` through the data-access layer from the start, even though v1 resolves it to a single constant tenant. All DDL and DML should be emitted schema-qualified rather than relying on a global `search_path`.

**Phase 5 — integrations and AI agent steps.** A generic outbound REST connector with pluggable auth, transform maps between systems, and a workflow step type that calls an LLM with tool-calling, structured output, and a human-approval gate before consequential actions. *v1 implication:* keep the record layer free of any HTTP concerns so integrations layer on rather than tangle in.

---

## Working instructions

1. Start with the Flyway bootstrap schema and the metadata model. Get `sys_db_object` and `sys_dictionary` right before anything else — everything else depends on their shape.
2. Then `IdentifierValidator` and `SchemaMutationService`, with tests, before any API exists. Prove you can create a physical table from metadata in a transaction.
3. Then the query layer, with the parser tested in isolation.
4. Then ACLs, then the REST API, then the frontend.
5. Commit in logical, reviewable increments with clear messages. Do not squash the whole build into one commit — the commit history is part of what a reviewer looks at.