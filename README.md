# Glidelike — a metadata-driven application platform

Define a table and its fields **at runtime** and immediately get a working
REST API, a list view, a form view and enforced access control — with zero
code written for that specific table. No entity classes, no controllers, no
per-table UI. This is the core of a ServiceNow-style platform (the Glide
engine's trick), built small enough to read in an afternoon.

Create a table called `incident`, add a `priority` field, and one second
later you can query it over REST and edit it in the UI.

## Quickstart

```bash
docker compose up --build
```

Then open **http://localhost:5173** and log in (password = username):

| user  | access |
|-------|--------|
| `admin` | everything, including the Schema Admin screen |
| `alice` | `itil` fulfiller (via the *Service Desk* group) — reads/writes all incidents |
| `bob`   | `itil` fulfiller (direct role) |
| `carol` | no roles — a condition ACL lets her read **only incidents assigned to her** |

The database is seeded on first start with a `task` table, an
`incident extends task` table, and 50 incidents — all created through the
platform's own APIs, which is the proof that the machinery works.

The API is on **http://localhost:8080**.

## API in 60 seconds

```bash
# log in, keep the token
TOKEN=$(curl -s localhost:8080/api/now/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"admin"}' | jq -r .result.token)
AUTH="Authorization: Bearer $TOKEN"

# query with a ServiceNow-style encoded query, dot-walking included
curl -s "localhost:8080/api/now/table/incident?sysparm_query=active=true^priority=1^ORpriority=2&sysparm_limit=5" -H "$AUTH" | jq
curl -s "localhost:8080/api/now/table/incident?sysparm_query=assigned_to.user_name=alice&sysparm_fields=number,short_description,assigned_to.user_name" -H "$AUTH" | jq

# resolved display values for references and choices
curl -s "localhost:8080/api/now/table/incident?sysparm_display_value=true&sysparm_limit=1" -H "$AUTH" | jq

# create a whole new table — this runs CREATE TABLE under the hood
curl -s localhost:8080/api/now/meta/table -H "$AUTH" -H 'Content-Type: application/json' -d '{
  "name": "problem", "label": "Problem", "extendsTable": "task",
  "fields": [
    {"element": "root_cause", "label": "Root cause", "type": "text"},
    {"element": "known_error", "label": "Known error", "type": "boolean", "defaultValue": "false"}
  ]}' | jq

# ...and use it immediately
curl -s localhost:8080/api/now/table/problem -H "$AUTH" -H 'Content-Type: application/json' \
  -d '{"short_description": "Recurring VPN drops", "root_cause": "Firmware bug"}' | jq

# add a field to a live table — ALTER TABLE, transactional with its metadata
curl -s localhost:8080/api/now/meta/table/problem/field -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"element": "workaround", "label": "Workaround", "type": "text"}' | jq

# record CRUD
curl -s "localhost:8080/api/now/table/incident/<sys_id>" -H "$AUTH" | jq
curl -s -X PATCH "localhost:8080/api/now/table/incident/<sys_id>" -H "$AUTH" \
  -H 'Content-Type: application/json' \
  -d '{"state": "6", "resolution_notes": "Rebooted the switch", "sys_mod_count": 0}' | jq
curl -s -X DELETE "localhost:8080/api/now/table/incident/<sys_id>" -H "$AUTH"
```

Endpoints:

```
POST   /api/now/auth/login                       username/password → JWT
GET    /api/now/table/{table}                    list (sysparm_query, sysparm_fields,
                                                 sysparm_limit, sysparm_offset,
                                                 sysparm_order_by, sysparm_display_value)
GET    /api/now/table/{table}/{sys_id}           read one
POST   /api/now/table/{table}                    create
PATCH  /api/now/table/{table}/{sys_id}           update (optional optimistic lock
                                                 via sys_mod_count in the body → 409)
DELETE /api/now/table/{table}/{sys_id}           delete
GET    /api/now/meta/tables                      tables the caller can read
GET    /api/now/meta/table/{table}               everything the UI renders from
POST   /api/now/meta/table                       create a table (runtime DDL)
POST   /api/now/meta/table/{table}/field         add a field (runtime DDL)
POST   /api/now/meta/table/{table}/field/{f}/choice   add a choice option
```

Responses use `{ "result": ... }` on success, `{ "error": { "message",
"detail" } }` on failure; list responses carry `X-Total-Count`. Status codes:
`400` bad query/value, `401` unauthenticated, `403` ACL denial (naming the
operation and field), `404` unknown table/record, `409` conflict.

## Why Postgres specifically

Postgres has **transactional DDL**: inserting the `sys_db_object` /
`sys_dictionary` rows and executing the `CREATE TABLE` happen in one
transaction, so the metadata and the physical schema cannot disagree — if
either fails, both roll back. Schema changes are additionally serialised with
an advisory lock so concurrent creates cannot interleave. Most databases
cannot give you this guarantee; it is the quiet foundation the whole
platform stands on.

## What's here

- **Encoded query language** — tokenizer + recursive-descent parser to an
  AST (`^` AND, `^OR`, `^NQ`, 14 operators), compiled to parameterised SQL.
  The same AST is evaluated in memory for ACL conditions on writes.
- **Joined-table inheritance** — `incident extends task` stores incident
  rows split across both physical tables, joined on read, written in one
  transaction; querying `task` returns descendants with `sys_class_name`.
- **ACLs enforced in the query layer** — read rules are compiled into the
  `WHERE` clause so denied rows are never fetched; a rule's condition may use
  `@me` for the current user. Default posture: deny (admin bypasses).
- **Runtime DDL with paranoid identifier validation** — one validator gates
  every identifier (regex + reserved words + `sys_` namespace); values are
  always bound parameters.
- **Metadata-driven UI** — login, navigator, list (filter builder + raw
  encoded query box), form (rendered by field type, reference typeahead,
  ACL-aware), schema admin.

See [ARCHITECTURE.md](ARCHITECTURE.md) for how a request flows from HTTP
through ACL to SQL, and [docs/adr/](docs/adr/) for the four decisions that
shape the design (runtime DDL vs JSONB/EAV, joined vs single-table
inheritance, no ORM, ACLs in the query layer).

## Development

Backend (needs a local Postgres, e.g. `docker compose up db`):

```bash
cd backend && ./mvnw spring-boot:run
```

Frontend dev server (proxies `/api` to :8080):

```bash
cd frontend && npm install && npm run dev
```

Tests (unit + Testcontainers integration; needs Docker):

```bash
cd backend && ./mvnw test
```

## Deliberate v1 limits

Dot-walking is one level deep and read-only. `^` cannot appear inside query
values. Mandatory is enforced by the record layer, not `NOT NULL`. Users,
groups, roles and ACLs are seeded, not yet administered via API. Workflow,
business rules, multi-tenancy and integrations are future phases — the
architecture leaves room for them (see the ADRs) but v1 does not pretend to
contain them.
