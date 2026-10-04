package com.sentinelai.detection;

import com.sentinelai.common.ReferenceGenerator;
import com.sentinelai.common.Severity;
import com.sentinelai.config.DetectionProperties;
import com.sentinelai.config.IngestionProperties;
import com.sentinelai.events.EventEntity;
import com.sentinelai.events.EventRepository;
import com.sentinelai.events.ServiceEntity;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentEventEntity;
import com.sentinelai.incidents.IncidentEventRepository;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.incidents.IncidentStatus;
import com.sentinelai.realtime.AfterCommitExecutor;
import com.sentinelai.realtime.IncidentUpdatePublisher;
import com.sentinelai.realtime.RealtimeEventType;
import com.sentinelai.timeline.TimelineEventTypes;
import com.sentinelai.timeline.TimelineService;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns an evaluated event into incident state.
 *
 * <p>One transaction covers the incident mutation, the evidence link and the audit
 * entry, so a client can never observe an incident that references an event the
 * database does not have, nor an audit trail describing a change that rolled
 * back. Broadcasts are registered as after-commit work for the same reason.
 *
 * <p>Resolution order is deliberate: correlation first (a downstream symptom
 * belongs to the incident that is already failing), then fingerprint reuse, and
 * only then opening something new.
 */
@Service
public class DetectionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DetectionOrchestrator.class);

    private final DetectionRuleRepository rules;
    private final EventRepository eventRepository;
    private final RuleEngine ruleEngine;
    private final FingerprintService fingerprints;
    private final CorrelationService correlation;
    private final IncidentRepository incidents;
    private final IncidentEventRepository incidentEvents;
    private final TimelineService timeline;
    private final ReferenceGenerator references;
    private final IncidentUpdatePublisher publisher;
    private final ApplicationEventPublisher events;
    private final DetectionProperties detectionProperties;
    private final IngestionProperties ingestionProperties;

    public DetectionOrchestrator(DetectionRuleRepository rules, EventRepository eventRepository,
                                 RuleEngine ruleEngine, FingerprintService fingerprints,
                                 CorrelationService correlation, IncidentRepository incidents,
                                 IncidentEventRepository incidentEvents, TimelineService timeline,
                                 ReferenceGenerator references, IncidentUpdatePublisher publisher,
                                 ApplicationEventPublisher events, DetectionProperties detectionProperties,
                                 IngestionProperties ingestionProperties) {
        this.rules = rules;
        this.eventRepository = eventRepository;
        this.ruleEngine = ruleEngine;
        this.fingerprints = fingerprints;
        this.correlation = correlation;
        this.incidents = incidents;
        this.incidentEvents = incidentEvents;
        this.timeline = timeline;
        this.references = references;
        this.publisher = publisher;
        this.events = events;
        this.detectionProperties = detectionProperties;
        this.ingestionProperties = ingestionProperties;
    }

    /**
     * Takes the stored event's id rather than the entity itself. The event was
     * committed by an earlier transaction, so re-reading it here both guarantees a
     * fully initialised graph and re-checks that the row really is committed
     * before any incident state is derived from it.
     */
    @Transactional
    public DetectionOutcome process(UUID eventId) {
        EventEntity event = eventRepository.findByIdWithService(eventId).orElse(null);
        if (event == null) {
            log.warn("Event {} vanished before detection could run", eventId);
            return DetectionOutcome.none(List.of());
        }

        List<RuleMatch> matches = ruleEngine.evaluate(
                rules.findByEventTypeAndEnabledTrueOrderByCodeAsc(event.getEventType()), event);

        if (matches.isEmpty()) {
            return DetectionOutcome.none(List.of());
        }

        // The most severe match owns the incident; the rest are corroborating.
        RuleMatch primary = matches.stream()
                .max(Comparator.comparingInt(match -> match.severity().rank()))
                .orElseThrow();

        List<String> matchedCodes = matches.stream().map(RuleMatch::ruleCode).toList();
        Instant now = Instant.now();

        Optional<IncidentEntity> correlated = correlation.findCorrelatedIncident(event, primary, now);
        if (correlated.isPresent()) {
            return attachCorrelated(correlated.get(), event, primary, matchedCodes, now);
        }

        String fingerprint = fingerprints.compute(event.getService().getId(), event.getErrorSignature());
        Optional<IncidentEntity> active = activeIncidentFor(fingerprint);
        if (active.isPresent()) {
            return recordOccurrence(active.get(), event, primary, matchedCodes, now);
        }
        return openIncident(event, primary, matchedCodes, fingerprint, now);
    }

    /**
     * An active incident always absorbs repeats of its fingerprint, however old.
     * Once resolved, the fingerprint is free again so the same outage can recur
     * as a new incident with its own history.
     */
    private Optional<IncidentEntity> activeIncidentFor(String fingerprint) {
        return incidents.findActiveByFingerprintFetchService(fingerprint, Limit.of(1))
                .stream().findFirst();
    }

    private DetectionOutcome attachCorrelated(IncidentEntity incident, EventEntity event, RuleMatch match,
                                              List<String> matchedCodes, Instant now) {
        IncidentEntity locked = incidents.findByIdForUpdate(incident.getId()).orElseThrow();
        locked.recordOccurrence(event.getOccurredAt(), match.severity());
        link(locked, event, IncidentEventEntity.Relationship.CORRELATED);

        boolean notify = locked.shouldNotify(now, match.dedupeWindowSeconds());
        long sequence = locked.getTimelineSeq();
        String summary = "Correlated symptom from " + event.getService().getName()
                + ": " + truncate(event.getMessage());

        if (notify) {
            locked.markNotified(now);
            sequence = timeline.append(locked, null, TimelineEventTypes.CORRELATED, summary,
                    evidencePayload(event, match, "CORRELATED"));
        }

        DetectionOutcome outcome = new DetectionOutcome(true, locked.getId(), locked.getReference(),
                DetectionOutcome.ACTION_CORRELATED, matchedCodes);
        if (notify) {
            publishAfterCommit(locked, RealtimeEventType.INCIDENT_UPDATED, sequence,
                    incidentSnapshot(locked, "A symptom from " + event.getService().getName()
                            + " was correlated into this incident."));
        }
        announceForInvestigation(locked, outcome);
        return outcome;
    }

    private DetectionOutcome recordOccurrence(IncidentEntity incident, EventEntity event, RuleMatch match,
                                              List<String> matchedCodes, Instant now) {
        IncidentEntity locked = incidents.findByIdForUpdate(incident.getId()).orElseThrow();
        Severity before = locked.getSeverity();
        locked.recordOccurrence(event.getOccurredAt(), match.severity());
        link(locked, event, IncidentEventEntity.Relationship.REPEAT);

        boolean notify = locked.shouldNotify(now, match.dedupeWindowSeconds());
        long sequence = locked.getTimelineSeq();
        boolean escalated = before.rank() < locked.getSeverity().rank();

        if (notify) {
            locked.markNotified(now);
            String summary = "Repeat occurrence #" + locked.getEventCount() + " via rule " + match.ruleCode();
            sequence = timeline.append(locked, null, TimelineEventTypes.OCCURRENCE, summary,
                    evidencePayload(event, match, "REPEAT"));
            if (escalated) {
                timeline.append(locked, null, TimelineEventTypes.SEVERITY_ESCALATED,
                        "Severity raised from " + before + " to " + locked.getSeverity(),
                        Map.of("from", before.name(), "to", locked.getSeverity().name()));
            }
        }

        DetectionOutcome outcome = new DetectionOutcome(true, locked.getId(), locked.getReference(),
                notify ? DetectionOutcome.ACTION_OCCURRENCE : DetectionOutcome.ACTION_SUPPRESSED, matchedCodes);

        if (notify) {
            publishAfterCommit(locked, RealtimeEventType.INCIDENT_UPDATED, sequence,
                    incidentSnapshot(locked, notify ? null : "Additional occurrences are being throttled."));
        }
        announceForInvestigation(locked, outcome);
        return outcome;
    }

    private DetectionOutcome openIncident(EventEntity event, RuleMatch primary, List<String> matchedCodes,
                                          String fingerprint, Instant now) {
        ServiceEntity service = event.getService();
        long activeForService = incidents.countByServiceIdAndStatusNot(service.getId(), IncidentStatus.RESOLVED);
        if (activeForService >= ingestionProperties.maxActiveIncidentsPerService()) {
            // A producer emitting endless distinct failures must not be able to
            // exhaust the dashboard. The event is still stored and searchable.
            log.warn("Refusing to open incident for {} ({}): active incident limit {} reached",
                    service.getName(), event.getSourceEventId(),
                    ingestionProperties.maxActiveIncidentsPerService());
            return new DetectionOutcome(true, null, null, DetectionOutcome.ACTION_THROTTLED, matchedCodes);
        }

        IncidentEntity incident = IncidentEntity.open(
                UUID.randomUUID(),
                references.nextIncidentReference(),
                fingerprint,
                buildTitle(service, event, primary),
                service,
                primary.severity(),
                primary.ruleCode(),
                event.getErrorSignature(),
                primary.correlationGroup(),
                primary.describe(),
                event.getOccurredAt());
        incident.markNotified(now);
        incidents.saveAndFlush(incident);
        link(incident, event, IncidentEventEntity.Relationship.TRIGGER);

        StringBuilder summary = new StringBuilder("Incident opened by rule ").append(primary.ruleCode());
        if (matchedCodes.size() > 1) {
            summary.append(" (corroborated by ").append(String.join(", ", matchedCodes)).append(")");
        }
        long sequence = timeline.append(incident, null, TimelineEventTypes.CREATED, summary.toString(),
                evidencePayload(event, primary, "TRIGGER"));

        // A just-resolved incident with this fingerprint means the problem came
        // back. Recording that now is what lets the AI module distinguish a
        // recurrence from something genuinely new.
        correlation.findRecentResolved(fingerprint, now).ifPresent(previous ->
                timeline.append(incident, null, "RECURRENCE_DETECTED",
                        "Looks like a recurrence of " + previous.getReference() + ", resolved at "
                                + previous.getResolvedAt(),
                        Map.of("previousIncidentId", previous.getId().toString(),
                                "previousReference", previous.getReference(),
                                "previousRootCause", previous.getResolvedRootCause() == null
                                        ? "" : previous.getResolvedRootCause())));

        publishAfterCommit(incident, RealtimeEventType.INCIDENT_CREATED, sequence,
                incidentSnapshot(incident, null));

        DetectionOutcome outcome = new DetectionOutcome(true, incident.getId(), incident.getReference(),
                DetectionOutcome.ACTION_OPENED, matchedCodes);
        announceForInvestigation(incident, outcome);
        return outcome;
    }

    /**
     * Announces the change so optional subsystems can react once it has committed.
     *
     * <p>Publishing inside the transaction is safe because every listener here runs
     * {@code AFTER_COMMIT}. Detection therefore stays unaware that an AI module
     * exists, and adding a second consumer later requires no change to this class.
     */
    private void announceForInvestigation(IncidentEntity incident, DetectionOutcome outcome) {
        if (incident.getId() == null) {
            return;
        }
        events.publishEvent(new IncidentDetectedEvent(incident.getId(), incident.getReference(), outcome.action()));
    }

    private void link(IncidentEntity incident, EventEntity event, IncidentEventEntity.Relationship relationship) {
        incidentEvents.save(new IncidentEventEntity(
                new IncidentEventEntity.Key(incident.getId(), event.getId()), relationship, Instant.now()));
    }

    private Map<String, Object> evidencePayload(EventEntity event, RuleMatch match, String relationship) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", event.getId().toString());
        payload.put("sourceEventId", event.getSourceEventId());
        payload.put("service", event.getService().getQualifiedName());
        payload.put("eventType", event.getEventType().name());
        payload.put("severity", event.getSeverity().name());
        payload.put("message", event.getMessage());
        payload.put("occurredAt", event.getOccurredAt().toString());
        payload.put("relationship", relationship);
        payload.put("ruleCode", match.ruleCode());
        if (match.observedValue() != null) {
            payload.put("observedValue", match.observedValue());
        }
        if (match.threshold() != null) {
            payload.put("threshold", match.threshold());
        }
        if (!event.getMetadata().isEmpty()) {
            payload.put("metadata", event.getMetadata());
        }
        return payload;
    }

    /**
     * The human-readable incident title.
     *
     * <p>Built from the event's raw message, not from its signature. The signature is a
     * grouping key — {@link ErrorSignatureExtractor} replaces every number and id with a
     * placeholder so near-identical messages collapse into one incident — which makes it
     * exactly the wrong thing to show a person: "connection pool utilisation &lt;num&gt;
     * percent" describes no observation at all. The signature still does its job, as the
     * fingerprint input and as the stored grouping key; it just stops being display text.
     *
     * <p>Falls back to the signature, then the event type, so a blank message still
     * yields something that names the problem rather than an empty title.
     */
    private String buildTitle(ServiceEntity service, EventEntity event, RuleMatch match) {
        String subject = firstNonBlank(event.getMessage(), event.getErrorSignature(), event.getEventType().name());
        return truncate(service.getName() + ": " + subject + " (" + match.ruleCode() + ")");
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.strip();
            }
        }
        return "unknown";
    }

    private Map<String, Object> incidentSnapshot(IncidentEntity incident, String note) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("incidentId", incident.getId().toString());
        snapshot.put("reference", incident.getReference());
        snapshot.put("title", incident.getTitle());
        snapshot.put("status", incident.getStatus().name());
        snapshot.put("severity", incident.getSeverity().name());
        snapshot.put("service", incident.getService().getQualifiedName());
        snapshot.put("eventCount", incident.getEventCount());
        snapshot.put("lastSeenAt", incident.getLastSeenAt().toString());
        snapshot.put("version", incident.getVersion());
        if (note != null) {
            snapshot.put("note", note);
        }
        return snapshot;
    }

    private void publishAfterCommit(IncidentEntity incident, RealtimeEventType type, long sequence,
                                    Map<String, Object> payload) {
        AfterCommitExecutor.run(() -> {
            publisher.publishFeed(type, incident.getId(), incident.getReference(), sequence, payload);
            publisher.publishIncident(type, incident.getId(), incident.getReference(), sequence, payload);
        });
    }

    private String truncate(String value) {
        return value.length() <= 300 ? value : value.substring(0, 299) + "…";
    }
}
