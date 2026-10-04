package com.sentinelai.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sentinel.investigation")
public record InvestigationProperties(

        /** {@code stub} (deterministic, offline) or {@code openai-compatible}. */
        @DefaultValue("stub") String provider,

        @DefaultValue("sentinel-stub-v1") String model,

        @DefaultValue("v1") String promptVersion,

        @DefaultValue("https://api.openai.com/v1") String openaiBaseUrl,

        @DefaultValue("") String openaiApiKey,

        @DefaultValue("20s") Duration requestTimeout,

        @DefaultValue("3") int maxAttempts,

        @DefaultValue("2s") Duration retryBackoff,

        /**
         * Queue poll interval, in milliseconds.
         *
         * <p>A bare number rather than a {@link Duration} because it is bound straight
         * into {@code @Scheduled(fixedDelayString=...)}, which parses either ISO-8601 or
         * a millisecond count but not Spring's {@code 1s} shorthand.
         */
        @DefaultValue("1000") long pollIntervalMs,

        @DefaultValue("5") int maxBatchSize,

        /** Caps on the evidence package, so one noisy incident cannot blow the context window. */
        @DefaultValue("40") int maxEvidenceEvents,

        @DefaultValue("12000") int maxEvidenceChars,

        @DefaultValue("4000") int maxRawTokens,

        /**
         * Artificial latency for the stub, so slow-provider behaviour can be exercised
         * without a network. Never consulted by the real provider.
         */
        @DefaultValue("120") long stubLatencyMs,

        /**
         * Fault injection for the stub: {@code timeout}, {@code server-error},
         * {@code rate-limited}, {@code unauthorized}, {@code malformed},
         * {@code injection-echo}, {@code slow}, or blank for healthy.
         */
        @DefaultValue("") String stubFailureMode,

        /**
         * Whether the background worker drains the analysis queue. On by default;
         * tests that assert on {@code QUEUED} state turn it off so a scheduled poll
         * cannot race the assertion.
         */
        @DefaultValue("true") boolean jobProcessorEnabled
) {
    public boolean isStubProvider() {
        return !"openai-compatible".equalsIgnoreCase(provider);
    }
}
