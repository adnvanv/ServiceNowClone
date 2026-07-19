# ADR-0001: Store user tables as real tables (runtime DDL), not JSONB or EAV

## Status
Accepted

## Context
A metadata-driven platform must store records for tables that do not exist at
compile time. Three storage models were considered:

1. **Runtime DDL** — physically `CREATE TABLE` / `ALTER TABLE` when the
   metadata changes; records are ordinary rows.
2. **JSONB** — one generic `record` table with a `jsonb data` column;
   metadata only describes what the JSON should contain.
3. **EAV** (entity–attribute–value) — one `values` table of
   `(record_id, field_id, value_text)` triples.

## Decision
Runtime DDL. This is also what ServiceNow itself does, which the platform
deliberately mirrors.

## Rationale
- **Real types and constraints.** `bigint`, `numeric`, `timestamptz`, FK
  constraints on reference fields — the database enforces integrity instead
  of the application re-implementing it over strings (EAV) or JSON.
- **Real indexes and query plans.** Encoded queries compile to plain SQL on
  plain columns; `priority <= 2 ORDER BY sys_created_on` uses ordinary
  indexes. EAV turns every multi-condition query into a self-join pile-up;
  JSONB pushes type coercion and collation subtleties into every predicate.
- **Legibility.** `psql`, backups, BI tools and future engineers all see a
  normal schema. The platform is inspectable with `\d incident`.
- **Postgres makes it safe.** Transactional DDL means the metadata insert and
  the `CREATE TABLE` commit or roll back together — the failure mode that
  makes runtime DDL scary on other databases (metadata says one thing, schema
  says another) cannot happen. An advisory lock serialises concurrent schema
  changes.

## Costs accepted
- DDL is generated from user input, so identifier injection is a real threat
  → mitigated by a single, paranoid, heavily tested `IdentifierValidator` and
  by always quoting identifiers.
- `ALTER TABLE ADD COLUMN` takes a brief exclusive lock; acceptable for
  administrative operations.
- A very large number of tables means a very large catalog; not a v1 concern.

## Rejected alternatives in one line each
- **JSONB**: schema drifts from data silently; weak typing; indexes need
  per-field expression indexes managed by hand — the metadata layer would end
  up reimplementing DDL anyway, badly.
- **EAV**: queries and reporting degrade catastrophically; every value is a
  string; referential integrity impossible. Historically known as the
  antipattern that kills platforms like this.
