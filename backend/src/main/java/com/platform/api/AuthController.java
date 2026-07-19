package com.platform.api;

import com.platform.core.error.UnauthorizedException;
import com.platform.core.security.JwtService;
import com.platform.core.security.UserContext;
import com.platform.core.security.UserService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/now/auth")
public class AuthController {

    public record LoginRequest(String username, String password) {
    }

    private final UserService userService;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    public AuthController(UserService userService, JwtService jwtService,
                          PasswordEncoder passwordEncoder) {
        this.userService = userService;
        this.jwtService = jwtService;
        this.passwordEncoder = passwordEncoder;
    }

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody LoginRequest request) {
        if (request.username() == null || request.password() == null) {
            throw new UnauthorizedException("Invalid credentials");
        }
        UserService.PlatformUser user = userService.findByUsername(request.username())
                .filter(UserService.PlatformUser::active)
                .filter(u -> passwordEncoder.matches(request.password(), u.passwordHash()))
                // One error for both unknown user and wrong password: do not
                // leak which usernames exist.
                .orElseThrow(() -> new UnauthorizedException("Invalid credentials"));

        String token = jwtService.issue(user.sysId(), user.userName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", token);
        result.put("user", userInfo(user));
        result.put("roles", userService.effectiveRoles(user.sysId()));
        return Map.of("result", result);
    }

    @GetMapping("/me")
    public Map<String, Object> me(UserContext context) {
        UserService.PlatformUser user = userService.findByUsername(context.username())
                .orElseThrow(() -> new UnauthorizedException("Unknown user"));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("user", userInfo(user));
        result.put("roles", context.roles());
        return Map.of("result", result);
    }

    private static Map<String, Object> userInfo(UserService.PlatformUser user) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("sys_id", user.sysId());
        info.put("user_name", user.userName());
        info.put("first_name", user.firstName());
        info.put("last_name", user.lastName());
        info.put("email", user.email());
        return info;
    }
}
