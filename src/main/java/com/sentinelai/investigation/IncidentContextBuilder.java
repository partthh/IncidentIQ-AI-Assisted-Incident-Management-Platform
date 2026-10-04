package com.sentinelai.investigation;

import com.sentinelai.common.Severity;
import com.sentinelai.config.InvestigationProperties;
import com.sentinelai.events.EventEntity;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentEventEntity;
import com.sentinelai.incidents.IncidentEventRepository;
import com.sentinelai.timeline.TimelineEntryEntity;
import com.sentinelai.timeline.TimelineRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles the evidence package for one incident.
 *
 * <p>This is where an LLM call becomes safe and affordable. Three separate caps
 * apply, because each protects against a different failure:
 * <ul>
 *   <li>{@code maxEvidenceEvents} bounds token count,</li>
 *   <li>{@code maxEvidenceChars} bounds the rendered text regardless of row count,</li>
 *   <li>event <em>ordering</em> bounds relevance: the newest events are kept, since
 *       during an active failure the current state is the informative one.</li>
 * </ul>
 *
 * <p>Derived aggregates (metric min/max/latest, dependency tallies) are added
 * deliberately: they let the model reason about a trend without the prompt having to
 * contain every raw row, and they make hypotheses easier to cite precisely.
 */
@Component
public class IncidentContextBuilder {

    private static final int MAX_TIMELINE_ITEMS = 25;
    private static final int MAX_DEPENDENCIES = 10;

    private final IncidentEventRepository incidentEvents;
    private final TimelineRepository timelineRepository;
    private final IncidentKnowledgeService knowledge;
    private final InvestigationProperties properties;

    public IncidentContextBuilder(IncidentEventRepository incidentEvents, TimelineRepository timelineRepository,
                                  IncidentKnowledgeService knowledge, InvestigationProperties properties) {
        this.incidentEvents = incidentEvents;
        this.timelineRepository = timelineRepository;
        this.knowledge = knowledge;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public IncidentContext build(IncidentEntity incident) {
        List<IncidentEventEntity> links = incidentEvents.findByIncidentIdWithEvent(
                incident.getId(), Limit.of(properties.maxEvidenceEvents()));
        List<EventEntity> events = links.stream()
                .map(IncidentEventEntity::getEvent)
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(EventEntity::getOccurredAt))
                .toList();

        List<IncidentContext.EvidenceEvent> evidenceEvents = new ArrayList<>(events.size());
        for (EventEntity event : events) {
            evidenceEvents.add(new IncidentContext.EvidenceEvent(
                    event.getId().toString(),
                    event.getOccurredAt(),
                    event.getEventType().name(),
                    event.getSeverity(),
                    event.getService().getQualifiedName(),
                    event.getMessage(),
                    Map.copyOf(event.getMetadata())));
        }

        return new IncidentContext(
                incident.getId(),
                incident.getReference(),
                incident.getTitle(),
                incident.getService().getQualifiedName(),
                incident.getService().getEnvironment(),
                incident.getService().getOwnerTeam(),
                incident.getSeverity(),
                incident.getStatus().name(),
                incident.getFirstSeenAt(),
                incident.getLastSeenAt(),
                incident.getEventCount() == null ? 0 : incident.getEventCount(),
                evidenceEvents.size(),
                Math.max(0, incident.getEventCount() - evidenceEvents.size()),
                incident.getDetectionRuleCode(),
                incident.getErrorSignature(),
                incident.getCorrelationGroup(),
                evidenceEvents,
                summariseMetrics(events),
                summariseDependencies(events),
                timelineRepository
                        .findByIncidentIdOrderBySequenceNoAsc(incident.getId(), Limit.of(MAX_TIMELINE_ITEMS))
                        .stream()
                        .map(entry -> new IncidentContext.TimelineItem(
                                entry.getCreatedAt(), entry.getEventType(), entry.getSummary()))
                        .toList(),
                knowledge.findSimilarResolved(incident));
    }

    /**
     * Renders the event list for the prompt, honouring the character budget by
     * dropping the oldest events rather than truncating mid-line.
     */
    public String renderEvidence(IncidentContext context) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        int included = 0;
        for (IncidentContext.EvidenceEvent event : context.events()) {
            String line = renderEvent(event);
            if (used + line.length() > properties.maxEvidenceChars() && included > 0) {
                break;
            }
            sb.append(line);
            used += line.length();
            included++;
        }
        return sb.toString();
    }

    private String renderEvent(IncidentContext.EvidenceEvent event) {
        StringBuilder line = new StringBuilder();
        line.append("  [event ").append(event.eventId()).append("] ")
                .append(event.occurredAt()).append(' ')
                .append(event.eventType()).append('/').append(event.severity())
                .append(' ').append(event.service()).append(": ")
                .append(InvestigationPrompt.oneLine(event.message()));
        if (!event.metadata().isEmpty()) {
            line.append("\n      metadata: ").append(renderMetadata(event.metadata()));
        }
        line.append('\n');
        return line.toString();
    }

    private String renderMetadata(Map<String, Object> metadata) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (count++ > 0) {
                sb.append(", ");
            }
            sb.append(entry.getKey()).append('=')
                    .append(InvestigationPrompt.oneLine(String.valueOf(entry.getValue())));
            if (sb.length() > 400) {
                sb.append(", …");
                break;
            }
        }
        return sb.toString();
    }

    private List<IncidentContext.MetricSummary> summariseMetrics(List<EventEntity> events) {
        Map<String, List<Double>> byKey = new TreeMap<>();
        for (EventEntity event : events) {
            event.getMetadata().forEach((key, value) -> {
                Double numeric = asNumber(value);
                if (numeric != null) {
                    byKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(numeric);
                }
            });
        }
        List<IncidentContext.MetricSummary> summaries = new ArrayList<>(byKey.size());
        byKey.forEach((key, values) -> summaries.add(new IncidentContext.MetricSummary(
                key,
                values.size(),
                values.get(0),
                values.get(values.size() - 1),
                values.stream().mapToDouble(Double::doubleValue).min().orElse(0d),
                values.stream().mapToDouble(Double::doubleValue).max().orElse(0d))));
        return summaries;
    }

    private List<IncidentContext.DependencySignal> summariseDependencies(List<EventEntity> events) {
        Map<String, List<EventEntity>> byDependency = new LinkedHashMap<>();
        for (EventEntity event : events) {
            Object dependency = event.getMetadata().get("dependency");
            if (dependency instanceof String name && !name.isBlank()) {
                byDependency.computeIfAbsent(name.trim(), ignored -> new ArrayList<>()).add(event);
            }
        }
        return byDependency.entrySet().stream()
                .sorted(Map.Entry.<String, List<EventEntity>>comparingByValue(
                        Comparator.comparingInt(List<EventEntity>::size).reversed()))
                .limit(MAX_DEPENDENCIES)
                .map(entry -> {
                    List<EventEntity> occurrences = entry.getValue();
                    return new IncidentContext.DependencySignal(
                            entry.getKey(),
                            occurrences.size(),
                            occurrences.get(0).getOccurredAt(),
                            occurrences.get(occurrences.size() - 1).getOccurredAt(),
                            occurrences.get(occurrences.size() - 1).getMessage());
                })
                .toList();
    }

    private Double asNumber(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }
}
