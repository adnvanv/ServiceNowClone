# ADR-0004: ACLs enforced in the query layer, not in controllers

## Status
Accepted

## Context
Access control could live in the HTTP layer (annotations, per-endpoint
checks) or below it, inside the data access path itself.

## Decision
All ACL evaluation happens inside `RecordService` — the single choke point
every read and mutation flows through. Controllers cannot bypass it because
`RecordQuery.fetch()`/`count()` require a `UserContext` and there is no other
way to execute a query.

For **read**, the winning ACL rules' conditions are compiled into the SQL
`WHERE` clause. Denied rows are never fetched, pagination and counts operate
on the filtered set, and there is no "load then filter" window where
protected data exists in application memory. Field-level read denials strip
columns from each returned record. For **create/write/delete**, rules are
evaluated against the affected record before any SQL runs; failures are 403s
naming the operation and field.

## Rationale
- **A controller cannot forget a check it never had to make.** New endpoints,
  the seeder, future business rules and workflow steps all inherit
  enforcement for free because they all go through the same layer.
- **Row filtering must be in SQL anyway.** "carol sees only incidents
  assigned to her" cannot be paginated correctly by post-filtering in memory
  — offset 20 of *her* incidents is not offset 20 of all incidents. Compiling
  the condition into WHERE is the only correct implementation, and once it
  exists, putting the rest of enforcement at the same depth is the consistent
  choice.
- **Conditions are already queries.** ACL conditions use the encoded query
  language, so read enforcement is literally `AND`-ing two ASTs. Write
  enforcement reuses the same AST with an in-memory evaluator
  (`QueryMatcher`) whose semantics mirror the SQL compiler's.

## Default posture
No matching rule → **deny** (except `admin`, which bypasses evaluation).
Closed-by-default means forgetting to write an ACL fails safe — the demo
seeds sensible ACLs so this is not annoying in practice.

## Costs accepted
- Every list query carries the ACL condition's WHERE fragment — negligible
  against the correctness gained.
- UI capability hints (should this field be read-only?) sometimes evaluate
  without a record, where condition rules are treated optimistically; the
  record layer re-checks with the real record on every actual access.
