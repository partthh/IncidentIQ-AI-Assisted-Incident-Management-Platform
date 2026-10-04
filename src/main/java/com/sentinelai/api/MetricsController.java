package com.sentinelai.api;

import com.sentinelai.common.Severity;
import com.sentinelai.events.EventRepository;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.incidents.IncidentStatus;
import com.sentinelai.investigation.AiAnalysisRepository;
import com.sentinelai.investigation.AnalysisStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard counters.
 *
 * <p>Two things this endpoint deliberately does not do.
 *
 * <p>It does not compute rates ("MTTR", "noisy service index"). Those need a
 * denominator and a time window agreed in advance, and a dashboard that invents one
 * is worse than a dashboard that shows nothing: the number gets quoted, the
 * definition gets forgotten, and the first engineer asked to reproduce it cannot.
 * Counters are unambiguous; the raw material for any rate is on the incident feed.
 *
 * <p>It does not cache. The counts come from indexed aggregate queries, and a cache
 * here would mean publishing a second, differently-fresh set of numbers with no
 * indication of their age. Read the database.
 */
@RestController
@RequestMapping("/api/v1/metrics")
public class MetricsController {

    private final IncidentRepository incidents;
    private final AiAnalysisRepository analyses;
    private final EventRepository events;

    public MetricsController(IncidentRepository incidents, AiAnalysisRepository analyses,
                             EventRepository events) {
        this.incidents = incidents;
        this.analyses = analyses;
        this.events = events;
    }

    @GetMapping("/summary")
    public Summary summary() {
        Map<IncidentStatus, Long> byStatus = new LinkedHashMap<>();
        for (IncidentStatus status : IncidentStatus.values()) {
            byStatus.put(status, incidents.countByStatus(status));
        }

        Map<Severity, Long> activeBySeverity = new LinkedHashMap<>();
        for (Severity severity : Severity.values()) {
            activeBySeverity.put(severity, incidents.countBySeverityAndStatus(severity,
                    IncidentStatus.RESOLVED));
        }

        Map<AnalysisStatus, Long> analysesByStatus = new LinkedHashMap<>();
        for (AnalysisStatus status : AnalysisStatus.values()) {
            analysesByStatus.put(status, analyses.countByStatus(status));
        }

        List<ServiceLoad> busiest = incidents.countActiveByServiceName().stream()
                .limit(8)
                .map(row -> new ServiceLoad((String) row[0], ((Number) row[1]).longValue()))
                .toList();

        Double meanDuration = analyses.averageCompletedDurationMs();

        return new Summary(
                Instant.now(),
                incidents.countActive(),
                incidents.countResolved(),
                byStatus,
                activeBySeverity,
                busiest,
                new AnalysisSummary(
                        analyses.count(),
                        analysesByStatus,
                        // null when nothing has completed yet. Rendering 0 would claim
                        // a latency was measured; null says the question is unanswerable.
                        meanDuration == null ? null : Math.round(meanDuration),
                        meanDuration == null ? 0L : analyses.countByStatus(AnalysisStatus.COMPLETED)),
                events.count());
    }

    /**
     * @param meanDurationMs  null when no analysis has completed; every number here is
     *                        a count of real rows, never a modelled estimate
     */
    public record Summary(
            Instant generatedAt,
            long activeIncidents,
            long resolvedIncidents,
            Map<IncidentStatus, Long> incidentsByStatus,
            Map<Severity, Long> activeIncidentsBySeverity,
            List<ServiceLoad> activeIncidentsByService,
            AnalysisSummary ai,
            long ingestedEvents) {
    }

    public record ServiceLoad(String service, long activeIncidents) {
    }

    public record AnalysisSummary(long total, Map<AnalysisStatus, Long> byStatus,
                                  Long meanDurationMs, long completedCount) {
    }

    /**
     * Reason the feed exists at all: "is the AI layer healthy, and is it cheap".
     *
     * <p>{@code queued} and {@code running} are the backlog. A backlog that only
     * grows means the model provider is slower than incident arrival, which is an
     * operational problem no amount of dashboard colour will fix on its own.
     */
    @GetMapping("/queue")
    public Queue queue() {
        return new Queue(
                analyses.countByStatus(AnalysisStatus.QUEUED),
                analyses.countByStatus(AnalysisStatus.RUNNING),
                analyses.countByStatus(AnalysisStatus.REJECTED),
                analyses.countByStatus(AnalysisStatus.FAILED),
                List.of(AnalysisStatus.values()));
    }

    public record Queue(long queued, long running, long rejected, long failed, List<AnalysisStatus> knownStatuses) {
    }
}