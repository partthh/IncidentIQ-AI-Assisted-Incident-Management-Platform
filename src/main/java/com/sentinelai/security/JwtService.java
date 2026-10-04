package com.sentinelai.security;

import com.sentinelai.common.Role;
import com.sentinelai.config.SecurityProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies HS256 access tokens.
 *
 * <p>The token carries identity and role only — never incident data or evidence
 * — so revoking a session or changing detection configuration never requires
 * waiting for tokens to expire.
 */
@Service
public class JwtService {

    private static final String CLAIM_ROLE = "role";
    private static final String CLAIM_NAME = "name";

    private final SecretKey key;
    private final SecurityProperties properties;

    public JwtService(SecurityProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.jwtSecret().getBytes(StandardCharsets.UTF_8));
    }

    public IssuedToken issue(AppUser user) {
        Instant now = Instant.now();
        Instant expiry = now.plus(properties.accessTokenTtl());
        String token = Jwts.builder()
                .issuer(properties.issuer())
                .subject(user.getId().toString())
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim(CLAIM_NAME, user.getName())
                .claim("email", user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiry))
                .id(UUID.randomUUID().toString())
                .signWith(key)
                .compact();
        return new IssuedToken(token, expiry, user.getRole());
    }

    /**
     * Verifies signature, issuer and expiry. Returns empty rather than throwing so
     * callers can treat an invalid token exactly like a missing one.
     */
    public Optional<SentinelPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Role role;
            try {
                role = Role.valueOf(claims.get(CLAIM_ROLE, String.class));
            } catch (IllegalArgumentException | NullPointerException ex) {
                return Optional.empty();
            }
            return Optional.of(new SentinelPrincipal(
                    UUID.fromString(claims.getSubject()),
                    claims.get("email", String.class),
                    claims.get(CLAIM_NAME, String.class),
                    role));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String token, Instant expiresAt, Role role) {
    }
}
