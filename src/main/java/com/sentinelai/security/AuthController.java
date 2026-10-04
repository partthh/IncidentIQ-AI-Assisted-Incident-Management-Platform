package com.sentinelai.security;

import com.sentinelai.common.Role;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthController(AppUserRepository users, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        AppUser user = users.findByEmailIgnoreCase(request.email().trim())
                .filter(candidate -> passwordEncoder.matches(request.password(), candidate.getPasswordHash()))
                // Identical response for unknown user and wrong password: revealing which
                // failed would turn this endpoint into a user-enumeration oracle.
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password"));

        JwtService.IssuedToken token = jwtService.issue(user);
        return ResponseEntity.ok(new LoginResponse(
                token.token(),
                "Bearer",
                token.expiresAt(),
                new UserView(user.getId(), user.getName(), user.getEmail(), user.getRole())));
    }

    @GetMapping("/me")
    public ResponseEntity<UserView> me() {
        SentinelPrincipal principal = CurrentUser.require();
        return ResponseEntity.ok(new UserView(
                principal.userId(), principal.name(), principal.email(), principal.role()));
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, Instant expiresAt, UserView user) {
    }

    public record UserView(UUID id, String name, String email, Role role) {
    }
}
