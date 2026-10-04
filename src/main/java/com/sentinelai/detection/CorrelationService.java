package com.sentinelai.detection;

import com.sentinelai.config.DetectionProperties;
import com.sentinelai.events.EventEntity;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.incidents.IncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;

/**
 * Decides whether a symptom belongs to an incident that is already open.
 *
 * <p>Correlation answers a different question from fingerprinting. Fingerprinting
 * asks "is this the same symptom on the same service?". Correlation asks "is this a
 * <em>consequence</em> of something that is already failing?".
 *
 * <p>The rules are intentionally conservative, because wrongly merging incidents
 * destroys more information than wrongly splitting them:
 * <ul>
 *   <li>The rule must declare a correlation group, so unrelated rules never merge.</li>
 *   <li>The existing incident must still be active and within the correlation
 *       window — a symptom that starts an hour later is a new problem.</li>
 *   <li>The originating service is never correlated into itself.</li>
 * </ul>
 */
@Component
public class CorrelationService {

    private final IncidentRepository incidents;
    private final DetectionProperties properties;

    public CorrelationService(IncidentRepository incidents, DetectionProperties properties) {
        this.incidents = incidents;
        this.properties = properties;
    }

    /**
     * @return an active incident this event is plausibly a symptom of, or empty
     */
    public Optional<IncidentEntity> findCorrelatedIncident(EventEntity event, RuleMatch match, Instant now) {
        if (match.correlationGroup() == null || match.correlationGroup().isBlank()) {
            return Optional.empty();
        }
        Instant windowStart = now.minus(properties.correlationWindow());
        return findActiveInGroup(match.correlationGroup(), now)
                .filter(incident -> !incident.getService().getId().equals(event.getService().getId()))
                .filter(incident -> incident.getFirstSeenAt().isAfter(windowStart)
                        || incident.getLastSeenAt().isAfter(windowStart));
    }

    private java.util.Optional<IncidentEntity> findActiveInGroup(String group, Instant now) {
        List<IncidentEntity> candidates = incidents.findByCorrelationGroupAndStatusNotOrderByLastSeenAtDesc(
                group, IncidentStatus.RESOLVED, Limit.of(10));
        return candidates.stream()
                .filter(incident -> incident.getLastSeenAt().isAfter(now.minus(properties.correlationWindow())))
                .max((a, b) -> a.getLastSeenAt().compareTo(b.getLastSeenAt()));
    }

    /**
     * The most recent resolved incident for this fingerprint, if it was closed
     * recently enough to be worth treating as history. Used to annotate a recurrence
     * and to seed the AI knowledge lookup.
     */
    public Optional<IncidentEntity> findRecentResolved(String fingerprint, Instant now) {
        return incidents.findByFingerprintAndStatusOrderByLastSeenAtDesc(
                        fingerprint, IncidentStatus.RESOLVED, Limit.of(1))
                .stream()
                .filter(incident -> incident.getResolvedAt() != null
                        && incident.getResolvedAt().isAfter(now.minus(properties.recentResolutionWindow())))
                .findFirst();
    }
}
