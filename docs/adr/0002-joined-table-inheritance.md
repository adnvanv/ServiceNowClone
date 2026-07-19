# ADR-0002: Joined-table (class-table) inheritance, not single-table

## Status
Accepted

## Context
`incident extends task` must behave as: incident has all of task's fields,
queries on task see incidents, fields live in exactly one place. Two standard
relational mappings: **single-table** (one physical table per hierarchy, all
descendant columns on it, sparse) and **joined-table** (one physical table
per class, child rows share the root's primary key).

## Decision
Joined-table inheritance, as ServiceNow uses for `task`. The child table
holds only its own columns plus
`sys_id uuid primary key references parent(sys_id) on delete cascade`.
The root table carries a stored `sys_class_name` column set on insert.

## Rationale
- **No sparse tables.** Single-table puts every descendant's columns on the
  root: with N children the root becomes a wall of mostly-NULL columns, and
  two children cannot both have a differently-typed field named `severity`.
- **Clean ownership.** A field's owning table is exactly the class that
  declared it — the same structure the metadata (`sys_dictionary.table_id`)
  already has. `InheritanceResolver` is a direct mapping, not a translation.
- **Root table as the hierarchy index.** Every record in the hierarchy has a
  row in the root table, so "query task, see all tasks and incidents" is a
  plain scan of one table, no UNIONs.

## The stored sys_class_name
Callers querying `task` need to know each row's actual class. Two options:
LEFT JOIN every child table and see which one hits, or store the class name
on the root row at insert. We store it: the answer never changes after
insert, and computing it would cost one LEFT JOIN per child on every read of
every parent table forever. One varchar column is the cheapest denormalisation
in the system.

## Costs accepted
- Reading a leaf class costs one JOIN per inheritance level. This is the
  well-known performance characteristic of deeply extended ServiceNow tables
  and is accepted knowingly: hierarchies here are shallow (2–3 levels) and
  the JOINs are on indexed same-value primary keys.
- Writes touch every table in the chain, so they must be transactional —
  which they are anyway.
