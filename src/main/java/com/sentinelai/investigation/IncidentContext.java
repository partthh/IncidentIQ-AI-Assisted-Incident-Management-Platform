package com.sentinelai.investigation;

import com.sentinelai.common.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The bounded evidence package handed to the model.
 *
 * <p>Bounded is the operative word. An incident can accumulate thousands of events;
 * the model gets a capped, ordered, structured slice plus derived aggregates. The
 * same object is persisted alongside the analysis, so a hypothesis can be re-checked
 * against exactly what the model saw rather than against whatever the logs look
 * like today.
 */
public record IncidentContext(
        UUID incidentId,
        String reference,
        String title,
        String service,
        String environment,
        String ownerTeam,
        Severity severity,
        String status,
        Instant firstSeenAt,
        Instant lastSeenAt,
        int eventCount,
        int eventsIncluded,
        int eventsTruncated,
        String detectionRule,
        String errorSignature,
        String correlationGroup,
        List<EvidenceEvent> events,
        List<MetricSummary> metrics,
        List<DependencySignal> dependencies,
        List<TimelineItem> timeline,
        List<PriorIncident> priorIncidents
) {

    /** A single observation, already redacted at ingestion. */
    public record EvidenceEvent(
            String eventId,
            Instant occurredAt,
            String eventType,
            Severity severity,
            String service,
            String message,
            Map<String, Object> metadata
    ) {
    }

    /** Min/max/latest over the included events, so trends need not be inferred from raw rows. */
    public record MetricSummary(String key, int samples, Double first, Double last, Double min, Double max) {
    }

    /** A dependency named repeatedly in the evidence, with how often it failed. */
    public record DependencySignal(String name, int occurrences, Instant firstSeen, Instant lastSeen,
                                   String sampleMessage) {
    }

    public record TimelineItem(Instant at, String eventType, String summary) {
    }

    /** A previously resolved incident, with the cause a human actually verified. */
    public record PriorIncident(String reference, String service, Instant resolvedAt, String rootCause,
                                String preventiveActions, double signatureOverlap) {
    }

    /** Ids a hypothesis is allowed to cite. */
    public AnalysisResponseValidator.EvidenceIndex evidenceIndex() {
        return AnalysisResponseValidator.EvidenceIndex.of(
                events.stream().map(EvidenceEvent::eventId).collect(java.util.stream.Collectors.toSet()));
    }
}
