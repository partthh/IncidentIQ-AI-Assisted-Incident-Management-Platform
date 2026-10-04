package com.sentinelai.timeline;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Read model for audit entries.
 *
 * <p>The actor is resolved to a name and role rather than exposed as a bare UUID: an
 * audit trail that answers "which Priya?" is useful, and one that answers "which of
 * these fourteen ids?" is not.
 */
public final class TimelineViews {

    private TimelineViews() {
    }

    public record Actor(UUID id, String name, String email, String role) {
    }

    /**
     * @param sequence the per-incident cursor; also the client's reconnect position
     */
    public record Entry(
            UUID id,
            long sequence,
            String eventType,
            String summary,
            Actor actor,
            Map<String, Object> payload,
            Instant createdAt
    ) {
    }
}