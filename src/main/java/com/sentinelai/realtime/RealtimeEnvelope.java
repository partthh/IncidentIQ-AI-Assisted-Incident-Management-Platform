package com.sentinelai.realtime;

import java.time.Instant;
import java.util.UUID;

/**
 * The single envelope every live update uses.
 *
 * <p>Two fields carry the reconnection story:
 * <ul>
 *   <li>{@code eventId} lets a client discard a message it has already applied,
 *       which matters because STOMP over a reconnecting TCP connection can
 *       redeliver.</li>
 *   <li>{@code sequence} is the incident's monotonic timeline cursor. A client
 *       that reconnects with its last seen sequence can ask the REST timeline for
 *       exactly what it missed.</li>
 * </ul>
 *
 * <p>Delivery is explicitly <em>not</em> durable: the database is the record,
 * this envelope is a notification. Nothing in the system may treat a missed
 * message as a lost state change.
 */
public record RealtimeEnvelope(
        String eventId,
        RealtimeEventType type,
        UUID incidentId,
        String incidentReference,
        Instant occurredAt,
        long sequence,
        Object payload
) {

    public static RealtimeEnvelope of(RealtimeEventType type, UUID incidentId, String incidentReference,
                                      long sequence, Object payload) {
        return new RealtimeEnvelope(
                "evt-" + UUID.randomUUID(),
                type,
                incidentId,
                incidentReference,
                Instant.now(),
                sequence,
                payload);
    }
}
