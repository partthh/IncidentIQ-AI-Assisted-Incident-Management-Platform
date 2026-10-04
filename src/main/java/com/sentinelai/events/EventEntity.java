package com.sentinelai.events;

import com.sentinelai.common.EventType;
import com.sentinelai.common.Severity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * An immutable observation from a monitored service.
 *
 * <p>{@code occurredAt} is the producer's clock and {@code receivedAt} is ours.
 * Keeping them separate means a delayed or out-of-order producer cannot corrupt
 * the incident timeline, which is always ordered by occurrence time.
 */
@Entity
@Table(name = "events")
public class EventEntity {

    @Id
    private UUID id;

    @Column(name = "source_scope", nullable = false, length = 80)
    private String sourceScope;

    @Column(name = "source_event_id", nullable = false, length = 200)
    private String sourceEventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Column(nullable = false, length = 4000)
    private String message;

    @Column(name = "error_signature", length = 200)
    private String errorSignature;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata_json", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> metadata = new LinkedHashMap<>();

    protected EventEntity() {
        // for JPA
    }

    public EventEntity(UUID id, String sourceScope, String sourceEventId, ServiceEntity service,
                       EventType eventType, Severity severity, String message, String errorSignature,
                       Instant occurredAt, Instant receivedAt, Map<String, Object> metadata) {
        this.id = id;
        this.sourceScope = sourceScope;
        this.sourceEventId = sourceEventId;
        this.service = service;
        this.eventType = eventType;
        this.severity = severity;
        this.message = message;
        this.errorSignature = errorSignature;
        this.occurredAt = occurredAt;
        this.receivedAt = receivedAt;
        this.metadata = metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata);
    }

    public UUID getId() {
        return id;
    }

    public String getSourceScope() {
        return sourceScope;
    }

    public String getSourceEventId() {
        return sourceEventId;
    }

    public ServiceEntity getService() {
        return service;
    }

    public EventType getEventType() {
        return eventType;
    }

    public Severity getSeverity() {
        return severity;
    }

    public String getMessage() {
        return message;
    }

    public String getErrorSignature() {
        return errorSignature;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    /**
     * Looks up a numeric measurement from metadata without assuming a type.
     * Detection rules must never throw on unexpected metadata shapes.
     */
    public Double numericMetadata(String key) {
        Object raw = metadata == null ? null : metadata.get(key);
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        if (raw instanceof String text) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
