package com.sentinelai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sentinel.security")
public record SecurityProperties(

        @DefaultValue("dev-only-secret-change-me-0123456789abcdef0123456789abcdef") String jwtSecret,

        @DefaultValue("12h") Duration accessTokenTtl,

        @DefaultValue("sentinel-ai") String issuer,

        /** Origins permitted to open the WebSocket handshake and call the API from a browser. */
        @DefaultValue("http://localhost:5173,http://localhost:3000") String[] allowedOrigins
) {
    public SecurityProperties {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "sentinel.security.jwt-secret must be at least 32 characters for HS256");
        }
    }
}
