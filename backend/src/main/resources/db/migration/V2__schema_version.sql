-- A single-row counter bumped in the same transaction as every schema
-- mutation. Each replica keeps its own in-memory SchemaCache, so when the
-- platform runs as several pods a change committed through one pod has to be
-- discoverable by the others. Polling one primary-key row is cheap and needs
-- no extra infrastructure (no Redis, no message bus); because the bump is part
-- of the DDL transaction, a replica can never see the new version without the
-- new schema also being committed.
create table sys_schema_version (
    id      integer primary key check (id = 1),
    version bigint  not null
);

insert into sys_schema_version (id, version) values (1, 0);
