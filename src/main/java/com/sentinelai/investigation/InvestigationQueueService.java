package com.sentinelai.investigation;

import com.sentinelai.common.NotFoundException;
import com.sentinelai.common.ReferenceGenerator;
import com.sentinelai.config.DetectionProperties;
import com.sentinelai.detection.IncidentDetectedEvent;
import com.sentinelai.config.IngestionProperties;
import com.sentinelai.config.InvestigationProperties;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.incidents.IncidentStatus;
import com.sentinelai.investigation.AnalysisViews.QueueResponse;
import com.sentinelai.investigation.llm.LlmClient;
import com.sentinelai.security.SentinelPrincipal;
import com.sentinelai.timeline.TimelineEventTypes;
import com.sentinelai.timeline.TimelineService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Decides <em>whether</em> an incident deserves an AI investigation, and records the
 * decision.
 *
 * <p>Kept separate from {@link AnalysisJobProcessor} on purpose. This class answers
 * "is this worth spending a model call on" and never performs one; the processor
 * answers "how do I get through the queue". Collapsing them would put the policy —
 * which encodes most of the cost control in the AI module — inside the retry loop,
 * where it is invisible and easily broken.
 *
 * <p>Suppression is normal, not exceptional. A quiet incident produces no analysis
 * most of the time, and the reasons are recorded so an operator can tell "we chose
 * not to look" from "we looked and failed".
 */
@Service
public class InvestigationQueueService {

    private static final Logger log = LoggerFactory.getLogger(InvestigationQueueService.class);

    /**
     * Ceiling on automatic investigations per incident.
     *
     * <p>A long-lived incident generating thousands of events would otherwise
     * trigger thousands of analyses, since each one sees a little more evidence than
     * the last. The engineer's explicit request is not subject to this — if a human
     * asks, they get an answer.
     */
    private static final int MAX_AUTOMATIC_ANALYSES_PER_INCIDENT = 3;

    private static final List<AnalysisStatus> PENDING = List.of(AnalysisStatus.QUEUED, AnalysisStatus.RUNNING);

    private final AiAnalysisRepository analyses;
    private final IncidentRepository incidents;
    private final TimelineService timeline;
    private final ReferenceGenerator references;
    private final LlmClient llmClient;
    private final IngestionProperties ingestionProperties;
    private final DetectionProperties detectionProperties;
    private final InvestigationProperties investigationProperties;

    public InvestigationQueueService(AiAnalysisRepository analyses, IncidentRepository incidents,
                                     TimelineService timeline, ReferenceGenerator references,
                                     LlmClient llmClient,
                                     IngestionProperties ingestionProperties,
                                     DetectionProperties detectionProperties,
                                     InvestigationProperties investigationProperties) {
        this.analyses = analyses;
        this.incidents = incidents;
        this.timeline = timeline;
        this.references = references;
        this.llmClient = llmClient;
        this.ingestionProperties = ingestionProperties;
        this.detectionProperties = detectionProperties;
        this.investigationProperties = investigationProperties;
    }

    /**
     * Reacts to detection, after the detection transaction has committed.
     *
     * <p>{@code AFTER_COMMIT} is what makes this safe to call from inside detection:
     * the incident row read here is the one clients can already see, and a failure
     * in this method cannot roll back a detection result. Publishing the event from
     * inside the detection transaction keeps the two modules from depending on each
     * other in code as well as in data.
     *
     * @param event      what detection changed
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIncidentDetected(IncidentDetectedEvent event) {
        if (!ingestionProperties.autoInvestigate()) {
            return;
        }
        considerForAutomaticInvestigation(event.incidentId(),
                IncidentDetectedEvent.SOURCE_AUTO_INVESTIGATE + ":" + event.action());
    }

    /**
     * The actual eligibility decision.
     *
     * <p>Package-visible rather than private so tests can drive it directly instead of
     * going through a full ingest-and-detect cycle to reach the AI branch.
     */
    @Transactional
    public void considerForAutomaticInvestigation(UUID incidentId, String reason) {
        IncidentEntity incident = incidents.findById(incidentId).orElse(null);
        if (incident == null) {
            return;
        }

        String suppression = suppressionReason(incident);
        if (suppression != null) {
            log.debug("Not queueing AI analysis for {}: {}", incident.getReference(), suppression);
            return;
        }

        // Lock the incident before checking for pending work. Detection also takes
        // this lock, so two simultaneous triggers serialise here: the first queues
        // and commits, the second then sees the pending row and backs off. Without
        // the lock both would observe "nothing pending" and queue duplicates.
        IncidentEntity locked = incidents.findByIdForUpdate(incidentId).orElse(null);
        if (locked == null) {
            return;
        }
        if (analyses.existsPendingForIncident(locked.getId(), PENDING)) {
            return;
        }
        if (analyses.countByIncidentId(locked.getId()) >= MAX_AUTOMATIC_ANALYSES_PER_INCIDENT) {
            return;
        }
        if (locked.getEventCount() == null
                || locked.getEventCount() < detectionProperties.autoInvestigateAfterEvents()) {
            return;
        }

        enqueue(locked, null, reason + " (event count " + locked.getEventCount() + ")");
    }

    /**
     * Explicitly requests an investigation on behalf of a human.
     *
     * <p>No event-count threshold and no per-incident cap: an engineer asking is
     * taken at their word. Only the "already running" check applies, because running
     * two analyses of the same incident concurrently costs money and tells the
     * engineer nothing they did not already have.
     */
    @Transactional
    public QueueResponse requestInvestigation(UUID incidentId, SentinelPrincipal actor, String note) {
        IncidentEntity incident = incidents.findByIdForUpdate(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));

        if (analyses.existsPendingForIncident(incidentId, PENDING)) {
            return new QueueResponse(
                    analyses.findFirstByIncidentIdAndStatusOrderByCreatedAtDesc(incidentId, AnalysisStatus.QUEUED)
                            .map(AiAnalysisEntity::getId).orElse(null),
                    analyses.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId).map(AiAnalysisEntity::getReference)
                            .orElse(null),
                    incident.getReference(),
                    AnalysisStatus.QUEUED,
                    "MANUAL_REQUEST",
                    "An analysis is already queued or running for this incident");
        }

        AiAnalysisEntity analysis = enqueue(incident, actor.userId(),
                note == null || note.isBlank() ? "MANUAL_REQUEST" : "MANUAL_REQUEST: " + note.trim());
        return new QueueResponse(analysis.getId(), analysis.getReference(), incident.getReference(),
                analysis.getStatus(), analysis.getTriggerReason(),
                "Investigation queued. Results appear on the incident and on this analysis record.");
    }

    /**
     * Creates the durable work item and records that it was queued.
     *
     * <p>Queuing writes a row <em>and</em> a timeline entry in one transaction. If
     * the process dies between the two the operator would never learn the analysis
     * was requested; if it died the other way round the timeline would claim work
     * that does not exist.
     */
    private AiAnalysisEntity enqueue(IncidentEntity incident, UUID requestedBy, String triggerReason) {
        Instant now = Instant.now();
        AiAnalysisEntity analysis = new AiAnalysisEntity(
                UUID.randomUUID(),
                references.nextAnalysisReference(),
                incident.getId(),
                llmClient.modelName(),
                investigationProperties.promptVersion(),
                AnalysisStatus.QUEUED,
                truncate(triggerReason, 60),
                requestedBy,
                now);
        analyses.save(analysis);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysisId", analysis.getId().toString());
        payload.put("analysisReference", analysis.getReference());
        payload.put("triggerReason", analysis.getTriggerReason());
        long sequence = timeline.append(incident, requestedBy, TimelineEventTypes.AI_QUEUED,
                "AI investigation queued (" + analysis.getTriggerReason() + ")", payload);

        log.info("Queued AI analysis {} for incident {} ({})",
                analysis.getReference(), incident.getReference(), analysis.getTriggerReason());
        return analysis;
    }

    /**
     * Why this incident is not eligible for automatic analysis, or {@code null}.
     *
     * <p>Each exclusion is a product decision, so each is named rather than merged
     * into a single boolean:
     * <ul>
     *   <li>A resolved incident is not worth a fresh hypothesis — its cause is
     *       already verified by a human.</li>
     *   <li>An unassigned, unacknowledged incident with a single occurrence is thin
     *       evidence; more usually arrives within seconds.</li>
     * </ul>
     */
    private String suppressionReason(IncidentEntity incident) {
        if (incident.getStatus() == IncidentStatus.RESOLVED) {
            return "already resolved";
        }
        if (incident.getEventCount() == null || incident.getEventCount() <= 0) {
            return "no events attached yet";
        }
        return null;
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}