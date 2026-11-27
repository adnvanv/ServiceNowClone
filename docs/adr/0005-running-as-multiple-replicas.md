# ADR-0005: Running the backend as multiple replicas

## Status
Accepted

## Context
The Helm chart runs the backend as a Deployment with three replicas (and an
HPA that can add more). Until then the platform had only ever run as one
process, and three pieces of state assumed that:

1. **Flyway migrations** run on every startup.
2. **`DemoDataSeeder`** checks "does `task` exist?" and, if not, seeds.
3. **`SchemaCache`** holds the whole metadata graph in memory and is reloaded
   after every schema change, but only in the process that made the change.

With three pods booting together against an empty database, or an admin
creating a table through one pod while the Ingress sends the next request to
another, each of these had to be examined.

## Decision

**Flyway: no change.** Flyway takes a Postgres advisory lock for the duration
of a migration run, so when three pods start at once one migrates and the
others wait, then find nothing left to apply. This is the "it should just
work" case, and it does.

**Seeder: a session-level advisory lock.** The seeder runs after Flyway as
application code, so Flyway's lock does not cover it. The naive
check-then-seed is a classic race: every pod sees an empty schema and every
pod seeds (duplicate users, or crashes on unique constraints that leave the
demo half-built). Seeding now runs while holding `pg_advisory_lock` on one
dedicated connection. The first pod seeds and the others block, then
re-check under the lock and skip. A *session*-level lock is used because
seeding spans many transactions. The schema service's
`pg_advisory_xact_lock` would release after the first commit. If the seeding
pod dies, Postgres ends its session and releases the lock.

**Schema cache: a version row, polled, with a check on a miss.**
`sys_schema_version` is a single-row counter bumped inside the same
transaction as every DDL change. Each replica polls it (default once per
second) and reloads its snapshot when the version moves. In addition,
`SchemaCache.require()` re-checks the version before answering "unknown
table". So "create a table on pod A, immediately insert into it on pod B"
works without waiting for the poll.

## Alternatives considered
- **Postgres LISTEN/NOTIFY**: push instead of poll, but needs a dedicated
  long-lived connection per pod, and a dropped connection silently loses
  notifications, so a polling fallback is required anyway.
- **Redis or a message bus for invalidation**: extra infrastructure for an
  event that is rare and administrative.
- **No cache, read metadata per request**: correct but puts the `sys_*`
  tables on the hot path of every read, which the design explicitly avoids.
- **Sticky sessions**: hides the problem for one user and breaks as soon as
  pods come and go (scale-out, rolling deploys, the chaos test).
- **A Kubernetes Job or init container for seeding**: workable, but the lock
  keeps `docker compose up` and `kubectl` deployments identical and needs no
  extra orchestration.

## Consequences
- After a schema change, another replica can show the old schema for at most
  one poll interval (about 1 second) when reading tables it already knows.
  Unknown-table lookups are consistent immediately.
- Each replica runs one primary-key lookup per second. With three replicas
  that is negligible.
- Tests cover both paths: `PlatformIntegrationTest.MultiReplica` runs the
  seeder concurrently and simulates a second replica's cache. CI deploys three
  real replicas and checks the seed ran exactly once.
