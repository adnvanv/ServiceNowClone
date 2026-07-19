package com.platform.core.tenant;

import org.springframework.stereotype.Component;

/**
 * Resolves the Postgres schema all DDL and DML is emitted against.
 *
 * v1 is single-tenant, so this always answers "public" — but every generated
 * statement is schema-qualified through this class rather than relying on the
 * connection's search_path. That is deliberate: Phase 4 (multi-tenancy) is
 * planned as schema-per-tenant, and retrofitting schema qualification into a
 * codebase that assumed a global search_path is the expensive kind of rework.
 * When Phase 4 lands, this becomes a request-scoped resolver (subdomain →
 * schema) and nothing downstream has to change.
 */
@Component
public class TenantContext {

    public String schema() {
        return "public";
    }
}
