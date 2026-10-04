package com.sentinelai.ingestion;

import com.sentinelai.common.EventType;
import com.sentinelai.common.Severity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;

/**
 * Ingestion request.
 *
 * <p>{@code sourceEventId} plus {@code sourceScope} is the producer-supplied
 * idempotency key: the unique constraint on {@code (source_scope,
 * source_event_id)} is what actually makes retries safe, so a producer that
 * retries after a timeout cannot create a duplicate event — and therefore cannot
 * create a duplicate incident.
 */
public record IngestEventRequest(

        @NotBlank
        @Size(max = 200, message = "must be at most 200 characters")
        String sourceEventId,

        /**
         * Producer namespace for the idempotency key. Optional so a simple
         * producer can omit it; the header {@code X-Sentinel-Source} takes
         * precedence when both are present.
         */
        @Size(max = 80)
        String sourceScope,

        @NotBlank
        @Size(max = 120)
        String service,

        @NotBlank
        @Size(max = 40)
        String environment,

        @NotNull(message = "is required")
        EventType eventType,

        @NotNull(message = "is required")
        Severity severity,

        @NotBlank
        @Size(max = 4000, message = "must be at most 4000 characters")
        String message,

        /**
         * Producer clock. Validated against our own clock to reject obviously
         * broken timestamps, then stored verbatim and never rewritten.
         */
        @NotNull(message = "is required")
        Instant occurredAt,

        Map<String, Object> metadata
) {
    public static final String DEFAULT_SCOPE = "default";
    public static final String SOURCE_HEADER = "X-Sentinel-Source";
}
