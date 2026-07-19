package com.platform.core.security;

import java.util.Set;
import java.util.UUID;

/**
 * The identity every data-access call runs as. Deliberately a dumb immutable
 * value: it is created once per request by the auth filter (or explicitly by
 * the seeder/tests) and passed down through RecordQuery/RecordService, so no
 * layer below the API ever reads thread-local security state.
 */
public record UserContext(UUID userId, String username, Set<String> roles) {

    public static final String ADMIN_ROLE = "admin";

    /** System identity for bootstrap/seeding — bypasses ACLs like any admin. */
    public static UserContext system() {
        return new UserContext(null, "system", Set.of(ADMIN_ROLE));
    }

    public boolean isAdmin() {
        return roles.contains(ADMIN_ROLE);
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
