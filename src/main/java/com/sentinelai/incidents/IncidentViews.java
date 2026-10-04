package com.sentinelai.incidents;

import com.sentinelai.common.HealthStatus;
import com.sentinelai.common.Severity;
import com.sentinelai.events.ServiceEntity;
import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import java.util.UUID;

/**
 * Read models for incidents.
 *
 * <p>Separate from the entity on purpose: the entity carries lazy proxies and
 * internal counters that clients have no business seeing, and mapping here keeps
 * the wire contract stable when the schema evolves.
 */
public final class IncidentViews {

    private IncidentViews() {
    }

    public record ServiceRef(UUID id, String name, String environment, HealthStatus healthStatus, String ownerTeam) {

        public static ServiceRef from(ServiceEntity service) {
            return new ServiceRef(service.getId(), service.getName(), service.getEnvironment(),
                    service.getHealthStatus(), service.getOwnerTeam());
        }
    }

    public record AssigneeRef(UUID id, String name, String email) {
    }

    /** Feed row: everything needed to render a list without a second request. */
    public record Summary(
            UUID id,
            String reference,
            String title,
            ServiceRef service,
            Severity severity,
            IncidentStatus status,
            AssigneeRef assignedTo,
            Instant firstSeenAt,
            Instant lastSeenAt,
            int eventCount,
            long timelineSeq,
            Long version
    ) {
        public static Summary from(IncidentEntity incident, AssigneeRef assignee) {
            return new Summary(
                    incident.getId(),
                    incident.getReference(),
                    incident.getTitle(),
                    ServiceRef.from(incident.getService()),
                    incident.getSeverity(),
                    incident.getStatus(),
                    assignee,
                    incident.getFirstSeenAt(),
                    incident.getLastSeenAt(),
                    incident.getEventCount() == null ? 0 : incident.getEventCount(),
                    incident.getTimelineSeq() == null ? 0L : incident.getTimelineSeq(),
                    incident.getVersion());
        }
    }

    /** Detail view: adds the evidence and the verified resolution. */
    public record Detail(
            UUID id,
            String reference,
            String title,
            ServiceRef service,
            Severity severity,
            IncidentStatus status,
            AssigneeRef assignedTo,
            Instant firstSeenAt,
            Instant lastSeenAt,
            int eventCount,
            long timelineSeq,
            Long version,
            String detectionRuleCode,
            String correlationGroup,
            String errorSignature,
            String triggerEvidence,
            Instant resolvedAt,
            String resolvedRootCause,
            String preventiveActions
    ) {
        public static Detail from(IncidentEntity incident, AssigneeRef assignee) {
            return new Detail(
                    incident.getId(),
                    incident.getReference(),
                    incident.getTitle(),
                    ServiceRef.from(incident.getService()),
                    incident.getSeverity(),
                    incident.getStatus(),
                    assignee,
                    incident.getFirstSeenAt(),
                    incident.getLastSeenAt(),
                    incident.getEventCount() == null ? 0 : incident.getEventCount(),
                    incident.getTimelineSeq() == null ? 0L : incident.getTimelineSeq(),
                    incident.getVersion(),
                    incident.getDetectionRuleCode(),
                    incident.getCorrelationGroup(),
                    incident.getErrorSignature(),
                    incident.getTriggerEvidence(),
                    incident.getResolvedAt(),
                    incident.getResolvedRootCause(),
                    incident.getPreventiveActions());
        }
    }

    /** Request bodies for the mutating endpoints. */
    public record AssignRequest(AssigneeRef assignee, Long expectedVersion) {

        /** Assigning to nobody unassigns, which is how an engineer steps away. */
        public boolean isUnassign() {
            return assignee == null;
        }
    }

    public record NoteRequest(String body, Long expectedVersion) {
    }

    /**
     * Resolution is the one transition that is irreversible and the one the knowledge
     * lookup is trained on, so a verified cause is mandatory. Declaring it here means
     * the caller gets a field-level error instead of a generic failure.
     */
    public record ResolveRequest(
            @NotBlank(message = "a verified root cause is required to resolve an incident")
            String rootCause,
            String preventiveActions,
            Long expectedVersion) {
    }

    public record AcknowledgeRequest(Long expectedVersion) {
    }
}
