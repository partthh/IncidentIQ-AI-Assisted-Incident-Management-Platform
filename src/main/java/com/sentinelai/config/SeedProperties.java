package com.sentinelai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "app.seed")
public record SeedProperties(

        /** Seeds demo users, services and detection rules. Never enable in production. */
        @DefaultValue("true") boolean enabled,

        @DefaultValue("sentinel123") String password
) {
}
