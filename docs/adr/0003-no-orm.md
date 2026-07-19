# ADR-0003: Spring JDBC, no JPA/Hibernate

## Status
Accepted

## Context
The natural Spring reflex is JPA. But an ORM of the Hibernate family is built
around **compile-time entity mappings**: classes annotated with columns,
loaded into a static metamodel at startup.

## Decision
`NamedParameterJdbcTemplate` everywhere. All SQL is generated dynamically
from the metadata graph. No JPA, no Hibernate, anywhere.

## Rationale
The schema is not known at compile time — it is not even known at *startup*,
since tables appear while the application runs. That violates the core
assumption ORMs are built on:

- There are no entity classes to map. Records are `Map`-shaped by nature.
- Hibernate's metamodel is fixed at bootstrap; a table created at 12:01
  cannot be mapped without restarting (or dynamic-map contortions that
  discard most of what the ORM provides).
- The interesting SQL here — inheritance-chain JOINs, dot-walk JOINs,
  ACL-injected WHERE clauses, generated DDL — is exactly the SQL an ORM wants
  to own and hide. We would be fighting the tool at every step.
- The safety an ORM normally provides (parameter binding, mapping
  discipline) is preserved by construction: `SqlBuilder` binds every value as
  a parameter and validates every identifier against the schema cache;
  `IdentifierValidator` gates every identifier into DDL.

## Costs accepted
- We write row-mapping and SQL assembly by hand. This is the honest cost of a
  dynamic schema, and it is concentrated in two classes (`SqlBuilder`,
  `RecordService`) rather than smeared across the codebase.
- No first-level cache / dirty checking — irrelevant for a stateless
  request-scoped API.
