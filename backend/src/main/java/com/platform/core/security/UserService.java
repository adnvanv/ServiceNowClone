package com.platform.core.security;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Reads users and resolves their effective roles: roles granted directly
 * plus roles granted to any group the user belongs to. Resolution happens
 * per request (in the auth filter), so revoking a role takes effect on the
 * next request, not the next login.
 */
@Service
public class UserService {

    public record PlatformUser(UUID sysId, String userName, String passwordHash,
                               String firstName, String lastName, String email, boolean active) {
    }

    private final NamedParameterJdbcTemplate jdbc;

    public UserService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<PlatformUser> findByUsername(String username) {
        return jdbc.query("""
                select sys_id, user_name, password_hash, first_name, last_name, email, active
                from sys_user where user_name = :u
                """,
                Map.of("u", username),
                (rs, i) -> new PlatformUser(
                        rs.getObject("sys_id", UUID.class),
                        rs.getString("user_name"),
                        rs.getString("password_hash"),
                        rs.getString("first_name"),
                        rs.getString("last_name"),
                        rs.getString("email"),
                        rs.getBoolean("active")))
                .stream().findFirst();
    }

    /** Direct roles ∪ roles of the user's groups. */
    public Set<String> effectiveRoles(UUID userId) {
        Set<String> roles = new HashSet<>(jdbc.queryForList("""
                select r.name from sys_role r
                join sys_user_has_role ur on ur.role_id = r.sys_id
                where ur.user_id = :id
                """, Map.of("id", userId), String.class));
        roles.addAll(jdbc.queryForList("""
                select r.name from sys_role r
                join sys_group_has_role gr on gr.role_id = r.sys_id
                join sys_group_member gm on gm.group_id = gr.group_id
                where gm.user_id = :id
                """, Map.of("id", userId), String.class));
        return roles;
    }

    public UserContext contextFor(PlatformUser user) {
        return new UserContext(user.sysId(), user.userName(), effectiveRoles(user.sysId()));
    }
}
