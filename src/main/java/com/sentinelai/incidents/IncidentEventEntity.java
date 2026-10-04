package com.sentinelai.incidents;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import com.sentinelai.events.EventEntity;

/**
 * Links an event to the incident that consumed it. The relationship type
 * distinguishes the alert that opened the incident from later repeats and from
 * downstream symptoms that were correlated into it.
 */
@Entity
@Table(name = "incident_events")
public class IncidentEventEntity {

    /** Composite key: an event belongs to an incident at most once. */
    @Embeddable
    public static class Key implements Serializable {

        @jakarta.persistence.Column(name = "incident_id")
        private UUID incidentId;

        @jakarta.persistence.Column(name = "event_id")
        private UUID eventId;

        protected Key() {
            // for JPA
        }

        public Key(UUID incidentId, UUID eventId) {
            this.incidentId = incidentId;
            this.eventId = eventId;
        }

        public UUID getIncidentId() {
            return incidentId;
        }

        public UUID getEventId() {
            return eventId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key other)) {
                return false;
            }
            return Objects.equals(incidentId, other.incidentId) && Objects.equals(eventId, other.eventId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(incidentId, eventId);
        }
    }

    @EmbeddedId
    private Key id;

    /**
     * The event this link points at. Read-only: {@code event_id} is already part of
     * the composite key, so it must not also be written as a foreign key column.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id", insertable = false, updatable = false)
    private EventEntity event;

    @Enumerated(EnumType.STRING)
    @Column(name = "relationship_type", nullable = false, length = 30)
    private Relationship relationship;

    @Column(name = "linked_at", nullable = false)
    private Instant linkedAt;

    protected IncidentEventEntity() {
        // for JPA
    }

    public IncidentEventEntity(Key id, Relationship relationship, Instant linkedAt) {
        this.id = id;
        this.relationship = relationship;
        this.linkedAt = linkedAt;
    }

    public Key getId() {
        return id;
    }

    public EventEntity getEvent() {
        return event;
    }

    public Relationship getRelationship() {
        return relationship;
    }

    public Instant getLinkedAt() {
        return linkedAt;
    }

    public enum Relationship {
        /** The alert that opened the incident. */
        TRIGGER,
        /** A later alert matching the same fingerprint. */
        REPEAT,
        /** A downstream symptom attached by correlation rather than by rule. */
        CORRELATED
    }
}
