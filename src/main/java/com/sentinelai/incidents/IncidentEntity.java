package com.sentinelai.incidents;

import com.sentinelai.common.Severity;
import com.sentinelai.events.ServiceEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/**
 * A group of correlated symptoms treated as one problem.
 *
 * <p>Two mechanisms keep concurrent writers honest:
 * <ul>
 *   <li>{@code fingerprint} — a deterministic hash of (service, rule, error
 *       signature). Repeated symptoms map to the same incident rather than
 *       creating new ones.</li>
 *   <li>{@code version} — JPA optimistic locking. Two engineers acknowledging
 *       at once cannot silently overwrite each other; the loser gets a 409.</li>
 * </ul>
 */
@Entity
@Table(name = "incidents")
public class IncidentEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @Column(nullable = false, length = 64)
    private String fingerprint;

    @Column(nullable = false, length = 300)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "service_id", nullable = false)
    private ServiceEntity service;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IncidentStatus status;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "detection_rule_code", length = 80)
    private String detectionRuleCode;

    @Column(name = "error_signature", length = 200)
    private String errorSignature;

    @Column(name = "correlation_group", length = 80)
    private String correlationGroup;

    @Column(name = "trigger_evidence", columnDefinition = "text")
    private String triggerEvidence;

    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;

    /**
     * When a repeat occurrence was last surfaced. Occurrences arriving inside a
     * rule's dedupe window still increment {@link #eventCount}, but do not each
     * produce a timeline entry or a live broadcast — this is the difference
     * between "one incident, 4 000 alerts" and "one incident, 4 000 updates".
     */
    @Column(name = "last_notified_at")
    private Instant lastNotifiedAt;

    @Column(name = "event_count", nullable = false)
    private Integer eventCount;

    /** Monotonic per-incident cursor used for reconnect reconciliation. */
    @Column(name = "timeline_seq", nullable = false)
    private Long timelineSeq;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "resolved_root_cause", columnDefinition = "text")
    private String resolvedRootCause;

    @Column(name = "preventive_actions", columnDefinition = "text")
    private String preventiveActions;

    protected IncidentEntity() {
        // for JPA
    }

    private IncidentEntity(UUID id, String reference, String fingerprint, String title,
                           ServiceEntity service, Severity severity, IncidentStatus status,
                           String detectionRuleCode, String errorSignature, String correlationGroup,
                           String triggerEvidence, Instant firstSeenAt, Instant lastSeenAt) {
        this.id = id;
        this.reference = reference;
        this.fingerprint = fingerprint;
        this.title = title;
        this.service = service;
        this.severity = severity;
        this.status = status;
        this.detectionRuleCode = detectionRuleCode;
        this.errorSignature = errorSignature;
        this.correlationGroup = correlationGroup;
        this.triggerEvidence = triggerEvidence;
        this.firstSeenAt = firstSeenAt;
        this.lastSeenAt = lastSeenAt;
        this.eventCount = 0;
        this.timelineSeq = 0L;
    }

    public static IncidentEntity open(UUID id, String reference, String fingerprint, String title,
                                      ServiceEntity service, Severity severity, String detectionRuleCode,
                                      String errorSignature, String correlationGroup, String triggerEvidence,
                                      Instant at) {
        return new IncidentEntity(id, reference, fingerprint, title, service, severity,
                IncidentStatus.OPEN, detectionRuleCode, errorSignature, correlationGroup,
                triggerEvidence, at, at);
    }

    /**
     * Records another occurrence of the same problem. Severity escalates
     * monotonically: a CRITICAL repeat must never downgrade a CRITICAL incident.
     */
    public void recordOccurrence(Instant at, Severity observedSeverity) {
        if (at.isAfter(this.lastSeenAt)) {
            this.lastSeenAt = at;
        }
        if (at.isBefore(this.firstSeenAt)) {
            this.firstSeenAt = at;
        }
        this.eventCount = this.eventCount + 1;
        if (observedSeverity != null && observedSeverity.isAtLeast(this.severity)) {
            this.severity = observedSeverity;
        }
    }

    public void changeStatus(IncidentStatus next) {
        this.status = next;
        if (next.isTerminal()) {
            this.resolvedAt = null;
        }
    }

    public void markResolved(Instant at) {
        this.resolvedAt = at;
        this.lastSeenAt = at;
    }

    public void assignTo(UUID userId) {
        this.assignedTo = userId;
    }

    public void recordResolution(String rootCause, String preventiveActions) {
        this.resolvedRootCause = rootCause;
        this.preventiveActions = preventiveActions;
    }

    public void bumpTimelineSequence() {
        this.timelineSeq = this.timelineSeq + 1;
    }

    /**
     * Whether this occurrence should be surfaced to humans, given the firing
     * rule's dedupe window.
     */
    public boolean shouldNotify(Instant now, int dedupeWindowSeconds) {
        return lastNotifiedAt == null
                || lastNotifiedAt.isBefore(now.minusSeconds(Math.max(0, dedupeWindowSeconds)));
    }

    public void markNotified(Instant at) {
        this.lastNotifiedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public String getTitle() {
        return title;
    }

    public ServiceEntity getService() {
        return service;
    }

    public Severity getSeverity() {
        return severity;
    }

    public IncidentStatus getStatus() {
        return status;
    }

    public UUID getAssignedTo() {
        return assignedTo;
    }

    public String getDetectionRuleCode() {
        return detectionRuleCode;
    }

    public String getErrorSignature() {
        return errorSignature;
    }

    public String getCorrelationGroup() {
        return correlationGroup;
    }

    public String getTriggerEvidence() {
        return triggerEvidence;
    }

    public Instant getFirstSeenAt() {
        return firstSeenAt;
    }

    public Instant getLastSeenAt() {
        return lastSeenAt;
    }

    public Instant getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public Long getTimelineSeq() {
        return timelineSeq;
    }

    public Long getVersion() {
        return version;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public String getResolvedRootCause() {
        return resolvedRootCause;
    }

    public String getPreventiveActions() {
        return preventiveActions;
    }

    public boolean isActive() {
        return status.isActive();
    }
}
