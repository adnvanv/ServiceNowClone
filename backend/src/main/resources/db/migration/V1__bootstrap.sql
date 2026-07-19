-- Bootstrap schema for the platform's own metadata ("sys_*") tables.
--
-- This is the ONLY schema Flyway manages. Every user-created table is made at
-- runtime via DDL generated from the rows in these tables — that is the whole
-- point of the platform. See docs/adr/0001-runtime-ddl.md.

create extension if not exists pgcrypto;

-- ---------------------------------------------------------------------------
-- sys_db_object: one row per table known to the platform.
-- "name" doubles as the physical Postgres table name, which is why it is
-- validated so aggressively before DDL (see IdentifierValidator).
-- ---------------------------------------------------------------------------
create table sys_db_object (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    name            text not null unique,
    label           text not null,
    super_class     uuid references sys_db_object(sys_id),
    is_extendable   boolean not null default false
);

-- ---------------------------------------------------------------------------
-- sys_dictionary: one row per field. (table_id, element) is the identity of a
-- column in a user table.
-- ---------------------------------------------------------------------------
create table sys_dictionary (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    table_id        uuid not null references sys_db_object(sys_id) on delete cascade,
    element         text not null,
    label           text not null,
    internal_type   text not null,
    max_length      int,
    reference_table uuid references sys_db_object(sys_id),
    mandatory       boolean not null default false,
    default_value   text,
    display         boolean not null default false,
    unique (table_id, element)
);

-- ---------------------------------------------------------------------------
-- sys_choice: options for fields with internal_type = 'choice'.
-- ---------------------------------------------------------------------------
create table sys_choice (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    table_id        uuid not null references sys_db_object(sys_id) on delete cascade,
    element         text not null,
    value           text not null,
    label           text not null,
    sequence        int not null default 0,
    unique (table_id, element, value)
);

-- ---------------------------------------------------------------------------
-- Users, groups, roles.
-- ---------------------------------------------------------------------------
create table sys_user (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    user_name       text not null unique,
    password_hash   text not null,
    first_name      text,
    last_name       text,
    email           text,
    active          boolean not null default true
);

create table sys_user_group (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    name            text not null unique,
    description     text
);

create table sys_group_member (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    group_id        uuid not null references sys_user_group(sys_id) on delete cascade,
    user_id         uuid not null references sys_user(sys_id) on delete cascade,
    unique (group_id, user_id)
);

create table sys_role (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    name            text not null unique,
    description     text
);

create table sys_user_has_role (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    user_id         uuid not null references sys_user(sys_id) on delete cascade,
    role_id         uuid not null references sys_role(sys_id) on delete cascade,
    unique (user_id, role_id)
);

create table sys_group_has_role (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    group_id        uuid not null references sys_user_group(sys_id) on delete cascade,
    role_id         uuid not null references sys_role(sys_id) on delete cascade,
    unique (group_id, role_id)
);

-- ---------------------------------------------------------------------------
-- ACLs. field is null for a table-level rule. condition is an encoded query
-- evaluated against the record being accessed. An ACL passes when the user
-- holds any of the roles in sys_acl_role (or the ACL lists no roles) AND the
-- condition (if any) matches. See docs/adr/0004-acl-in-query-layer.md.
-- ---------------------------------------------------------------------------
create table sys_security_acl (
    sys_id          uuid primary key default gen_random_uuid(),
    sys_created_on  timestamptz not null default now(),
    sys_created_by  text,
    sys_updated_on  timestamptz not null default now(),
    sys_updated_by  text,
    table_id        uuid not null references sys_db_object(sys_id) on delete cascade,
    field           text,
    operation       text not null check (operation in ('create', 'read', 'write', 'delete')),
    condition       text,
    active          boolean not null default true
);

create table sys_acl_role (
    sys_id          uuid primary key default gen_random_uuid(),
    acl_id          uuid not null references sys_security_acl(sys_id) on delete cascade,
    role_id         uuid not null references sys_role(sys_id) on delete cascade,
    unique (acl_id, role_id)
);

create index idx_dictionary_table on sys_dictionary(table_id);
create index idx_choice_table_element on sys_choice(table_id, element);
create index idx_acl_table on sys_security_acl(table_id);
