package com.sentinelai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sentinel.realtime")
public record RealtimeProperties(

        @DefaultValue("true") boolean enabled
) {
}
