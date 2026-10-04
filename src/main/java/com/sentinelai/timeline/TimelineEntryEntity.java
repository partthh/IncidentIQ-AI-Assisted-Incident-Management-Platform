package com.sentinelai.timeline;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Append-only audit record. Written in the <em>same transaction</em> as the
 * state change it describes, so the history can never disagree with the
 * incident it describes.
 *
 * <p>Rows are read as {@code (incident_id, sequence_no)}, which doubles as the
 * cursor a client uses to catch up after a dropped WebSocket connection.
 */
@Entity
@Table(name = "incident_timeline")
public class TimelineEntryEntity {

    @Id
    private UUID id;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    /** Null for system-generated entries. */
    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "event_type", nullable = false, length = 40)
    private String eventType;

    @Column(length = 500)
    private String summary;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "sequence_no", nullable = false)
    private Long sequenceNo;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TimelineEntryEntity() {
        // for JPA
    }

    public TimelineEntryEntity(UUID id, UUID incidentId, UUID actorId, String eventType, String summary,
                               Map<String, Object> payload, Long sequenceNo, Instant createdAt) {
        this.id = id;
        this.incidentId = incidentId;
        this.actorId = actorId;
        this.eventType = eventType;
        this.summary = summary;
        this.payload = payload == null ? new LinkedHashMap<>() : new LinkedHashMap<>(payload);
        this.sequenceNo = sequenceNo;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public UUID getActorId() {
        return actorId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getSummary() {
        return summary;
    }

    public Map<String, Object> getPayload() {
        return payload;
    }

    public Long getSequenceNo() {
        return sequenceNo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
