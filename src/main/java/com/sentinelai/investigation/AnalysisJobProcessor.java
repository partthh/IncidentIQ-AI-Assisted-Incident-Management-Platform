package com.sentinelai.investigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelai.config.InvestigationProperties;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.investigation.IncidentAnalysisService.Outcome;
import com.sentinelai.investigation.IncidentAnalysisService.RejectedAnalysisException;
import com.sentinelai.investigation.llm.LlmException;
import com.sentinelai.realtime.AfterCommitExecutor;
import com.sentinelai.realtime.IncidentUpdatePublisher;
import com.sentinelai.realtime.RealtimeEventType;
import com.sentinelai.timeline.TimelineEventTypes;
import com.sentinelai.timeline.TimelineService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Drains one batch of the AI analysis queue.
 *
 * <p>Work is a durable row rather than an in-memory task for one reason: the model
 * call is slow, remote and fallible, and an incident must not lose its investigation
 * because the process restarted mid-flight. A {@code QUEUED} row survives a crash; a
 * {@code RUNNING} row whose worker vanished is reclaimed by the stale-job sweep.
 *
 * <p>The transaction discipline is the important part. The model call happens
 * <em>outside</em> any transaction — see {@link IncidentAnalysisService} — and the
 * boundaries are:
 *
 * <pre>
 *   tx1  claim   QUEUED -&gt; RUNNING     (short, row-locked)
 *   --    model call                      (no connection held)
 *   tx2  record  RUNNING -&gt; COMPLETED | FAILED | REJECTED | QUEUED
 * </pre>
 *
 * <p>Holding a pooled connection across someone else's HTTP timeout would exhaust the
 * pool exactly when the system is busiest. Separating the two also means a slow
 * provider can never block ingestion, which is the property the whole design is built
 * around: AI suggests, rules detect.
 *
 * <p>{@link #poll()} is public and deliberately has no timer of its own: the timer
 * lives in {@link AnalysisQueueScheduler}. Keeping the two apart means the switch that
 * stops background draining also leaves a processor a test can drive by hand, instead
 * of making "no background worker" and "nothing to call" the same state.
 */
@Component
public class AnalysisJobProcessor {

    private static final Logger log = LoggerFactory.getLogger(AnalysisJobProcessor.class);

    /**
     * A RUNNING row older than this is assumed abandoned.
     *
     * <p>Must comfortably exceed {@code request-timeout * max-attempts + backoff}, or
     * healthy slow work would be reclaimed and billed twice.
     */
    private static final Duration STALE_AFTER = Duration.ofMinutes(15);

    private static final int MAX_STALE_RECLAIMS_PER_TICK = 10;

    private final AiAnalysisRepository analyses;
    private final IncidentRepository incidents;
    private final TimelineService timeline;
    private final IncidentAnalysisService analysisService;
    private final IncidentUpdatePublisher publisher;
    private final InvestigationProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate writeTransaction;

    public AnalysisJobProcessor(AiAnalysisRepository analyses, IncidentRepository incidents,
                                TimelineService timeline, IncidentAnalysisService analysisService,
                                IncidentUpdatePublisher publisher, InvestigationProperties properties,
                                ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
        this.analyses = analyses;
        this.incidents = incidents;
        this.timeline = timeline;
        this.analysisService = analysisService;
        this.publisher = publisher;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * One unit of work per tick.
     *
     * <p>Fixed delay rather than fixed rate, so a slow batch delays the next tick
     * instead of overlapping with it: two concurrent batches competing for the same
     * provider quota would turn a backlog into a self-inflicted rate limit.
     */
    public void poll() {
        reclaimStaleJobs();

        List<AiAnalysisEntity> pending = analyses.findByStatusOrderByCreatedAtAsc(
                AnalysisStatus.QUEUED, Limit.of(Math.max(1, properties.maxBatchSize())));
        if (pending.isEmpty()) {
            return;
        }
        log.debug("AI queue: {} job(s) pending", pending.size());
        for (AiAnalysisEntity candidate : pending) {
            try {
                process(candidate.getId());
            } catch (RuntimeException ex) {
                // One bad job must not stop the batch, and must not be retried forever.
                // The row has already reached a terminal or requeued state on every
                // path handled here; an unexpected throw means a bug, and retrying a
                // bug indefinitely is worse than logging it loudly.
                log.error("AI analysis job {} failed unexpectedly", candidate.getReference(), ex);
            }
        }
    }

    /**
     * Returns work abandoned by a crashed worker to the queue.
     *
     * <p>Only rows past the staleness threshold are touched. A young RUNNING row may
     * belong to a job this very JVM is executing, and reclaiming it would double-bill
     * the provider.
     *
     * <p>Transaction boundaries in this class are expressed with an explicit
     * {@link TransactionTemplate}, not {@code @Transactional}. These methods are called
     * from {@link #poll()} on {@code this}, which is self-invocation: a
     * {@code @Transactional} annotation would be bypassed by the proxy and the method
     * would silently run with no transaction. That is not a theoretical concern here —
     * the claim relies on a {@code PESSIMISTIC_WRITE} lock, which Hibernate refuses to
     * take outside a transaction, so every job would fail and every mutation would be
     * discarded on flush. Naming the template makes the boundary impossible to lose.
     */
    public void reclaimStaleJobs() {
        writeTransaction.executeWithoutResult(status -> {
            List<AiAnalysisEntity> stale = analyses.findStaleRunning(
                    Instant.now().minus(STALE_AFTER), Limit.of(MAX_STALE_RECLAIMS_PER_TICK));
            for (AiAnalysisEntity analysis : stale) {
                int attempts = analysis.getAttemptCount() == null ? 0 : analysis.getAttemptCount();
                if (attempts >= maxAttempts()) {
                    analysis.markFailed(AnalysisStatus.FAILED, "WORKER_LOST",
                            "The worker running this analysis stopped responding and the attempt budget is spent",
                            null, Instant.now());
                    log.warn("AI analysis {} abandoned after {} attempt(s)", analysis.getReference(), attempts);
                } else {
                    analysis.markRequeued("WORKER_LOST",
                            "Reclaimed from a worker that stopped responding");
                    log.warn("Reclaimed abandoned AI analysis {} (attempt {})",
                            analysis.getReference(), attempts);
                }
            }
        });
    }

    private void process(UUID analysisId) {
        Claimed job = claim(analysisId);
        if (job == null) {
            return;
        }

        try {
            Outcome outcome = analysisService.analyse(job.incident(), job.analysis().getReference());
            complete(job.analysis().getId(), outcome);
        } catch (RejectedAnalysisException rejection) {
            log.warn("AI analysis {} rejected by the validator: {} {}",
                    job.analysis().getReference(), rejection.getCode(), rejection.getMessage());
            reject(job.analysis().getId(), rejection.getCode(), rejection.getMessage(),
                    rejection.getRawResponse());
        } catch (LlmException failure) {
            handleProviderFailure(job.analysis().getId(), failure);
        }
    }

    /**
     * Claims a job: locks the row, re-checks it is still queued, then flips it to
     * {@code RUNNING}.
     *
     * <p>The re-check under lock is the whole point. Reading the status without a lock
     * leaves a window in which two pollers both see {@code QUEUED} and both bill the
     * provider for the same analysis.
     */
    protected Claimed claim(UUID analysisId) {
        return writeTransaction.execute(status -> {
            AiAnalysisEntity analysis = analyses.findByIdForUpdate(analysisId).orElse(null);
            if (analysis == null || analysis.getStatus() != AnalysisStatus.QUEUED) {
                return null;
            }
            IncidentEntity incident = incidents.findById(analysis.getIncidentId()).orElse(null);
            if (incident == null) {
                // The incident is gone. There is no timeline left to record this on, so the
                // row is retired rather than left pending forever.
                analysis.markFailed(AnalysisStatus.FAILED, "INCIDENT_GONE",
                        "The incident no longer exists", null, Instant.now());
                return null;
            }

            analysis.markRunning(Instant.now());
            broadcastStart(analysis, incident);
            return new Claimed(analysis, incident);
        });
    }

    /**
     * Finishes a job that came back valid.
     *
     * <p>Status, payload, evidence snapshot and audit entry are written together, so a
     * client that observes {@code COMPLETED} can always retrieve the analysis and the
     * timeline entry explaining it.
     */
    protected void complete(UUID analysisId, Outcome outcome) {
        writeTransaction.executeWithoutResult(status -> {
            AiAnalysisEntity analysis = analyses.findByIdForUpdate(analysisId).orElse(null);
            if (analysis == null || !analysis.getStatus().isPending()) {
                return;
            }
            IncidentEntity incident = incidents.findByIdForUpdate(analysis.getIncidentId()).orElse(null);
            if (incident == null) {
                analysis.markFailed(AnalysisStatus.FAILED, "INCIDENT_GONE",
                        "The incident no longer exists", null, Instant.now());
                return;
            }

            analysis.markCompleted(
                    outcome.toAnalysisJson(objectMapper),
                    outcome.toEvidenceSnapshot(objectMapper),
                    outcome.toUsage(outcome.response()),
                    outcome.response().rawContent(),
                    Instant.now());

            Map<String, Object> payload = analysisPayload(analysis, outcome);
            timeline.append(incident, analysis.getRequestedBy(), TimelineEventTypes.AI_COMPLETED,
                    "AI analysis " + analysis.getReference() + " completed with "
                            + outcome.analysis().hypotheses().size() + " hypothesis(es)", payload);

            Map<String, Object> snapshot = new LinkedHashMap<>(payload);
            snapshot.put("title", incident.getTitle());
            snapshot.put("severity", incident.getSeverity().name());
            snapshot.put("service", incident.getService().getQualifiedName());
            publishAfterCommit(RealtimeEventType.AI_ANALYSIS_COMPLETED, incident, analysis, snapshot);
        });
    }

    /**
     * Records a response that parsed but did not survive validation.
     *
     * <p>Rejection is terminal and not retried. Retrying untrusted output to see
     * whether the model says something different this time is how a system ends up
     * accepting an answer it previously judged untrustworthy. The raw response is
     * stored so a human can see exactly what was discarded and decide whether the
     * model or the validator is at fault.
     */
    protected void reject(UUID analysisId, String code, String message, String rawResponse) {
        writeTransaction.executeWithoutResult(status -> {
            AiAnalysisEntity analysis = analyses.findByIdForUpdate(analysisId).orElse(null);
            if (analysis == null || !analysis.getStatus().isPending()) {
                return;
            }
            IncidentEntity incident = incidents.findByIdForUpdate(analysis.getIncidentId()).orElse(null);
            if (incident == null) {
                analysis.markFailed(AnalysisStatus.FAILED, "INCIDENT_GONE",
                        "The incident no longer exists", null, Instant.now());
                return;
            }

            analysis.markFailed(AnalysisStatus.REJECTED, code, message, rawResponse, Instant.now());

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("analysisId", analysis.getId().toString());
            payload.put("analysisReference", analysis.getReference());
            payload.put("status", AnalysisStatus.REJECTED.name());
            payload.put("errorCode", code);
            payload.put("errorMessage", message);
            payload.put("attempts", analysis.getAttemptCount());
            timeline.append(incident, analysis.getRequestedBy(), TimelineEventTypes.AI_REJECTED,
                    "AI analysis " + analysis.getReference() + " was rejected (" + code + ")", payload);

            publishAfterCommit(RealtimeEventType.AI_ANALYSIS_FAILED, incident, analysis, payload);
        });
    }

    /**
     * Decides between requeueing and failing a job the provider could not complete.
     *
     * <p>A retryable failure returns to {@code QUEUED} until the attempt budget is spent,
     * because a timeout is a statement about the moment, not about the analysis. A
     * non-retryable one fails immediately: waiting three attempts to learn that a 401
     * will not become a 200 only delays the answer an operator is waiting for.
     */
    private void handleProviderFailure(UUID analysisId, LlmException failure) {
        String code = failure.getKind().name();
        boolean retryable = failure.isRetryable();

        AiAnalysisEntity analysis = writeTransaction.execute(status -> {
            AiAnalysisEntity locked = analyses.findByIdForUpdate(analysisId).orElse(null);
            if (locked == null || !locked.getStatus().isPending()) {
                return null;
            }
            int attempts = locked.getAttemptCount() == null ? 0 : locked.getAttemptCount();
            if (retryable && attempts < maxAttempts()) {
                // Keep the reason visible on the row while the job waits, so an operator
                // looking at a stalled queue can see what went wrong last time.
                locked.markRequeued(code, failure.getMessage());
                return locked;
            }
            IncidentEntity incident = incidents.findByIdForUpdate(locked.getIncidentId()).orElse(null);
            if (incident != null) {
                Map<String, Object> payload = failurePayload(locked, code, failure.getMessage());
                timeline.append(incident, locked.getRequestedBy(), TimelineEventTypes.AI_FAILED,
                        "AI analysis " + locked.getReference() + " failed (" + code + ")", payload);
            }
            locked.markFailed(AnalysisStatus.FAILED, code, failure.getMessage(),
                    failure.getRawResponse(), Instant.now());
            return locked;
        });

        if (analysis == null) {
            return;
        }
        if (analysis.getStatus() == AnalysisStatus.QUEUED) {
            log.info("AI analysis {} requeued after {} (attempt {}/{})", analysis.getReference(), code,
                    analysis.getAttemptCount(), maxAttempts());
            return;
        }
        incidents.findById(analysis.getIncidentId()).ifPresent(incident ->
                publishAfterCommit(RealtimeEventType.AI_ANALYSIS_FAILED, incident, analysis,
                        failurePayload(analysis, code, failure.getMessage())));
    }

    private Map<String, Object> analysisPayload(AiAnalysisEntity analysis, Outcome outcome) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysisId", analysis.getId().toString());
        payload.put("analysisReference", analysis.getReference());
        payload.put("status", AnalysisStatus.COMPLETED.name());
        payload.put("model", analysis.getModelName());
        payload.put("promptVersion", analysis.getPromptVersion());
        payload.put("summary", outcome.analysis().summary());
        payload.put("hypothesisCount", outcome.analysis().hypotheses().size());
        payload.put("hypotheses", outcome.analysis().hypotheses());
        payload.put("missingEvidence", outcome.analysis().missingEvidence());
        payload.put("caveats", outcome.analysis().caveats());
        return payload;
    }

    private Map<String, Object> failurePayload(AiAnalysisEntity analysis, String code, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysisId", analysis.getId().toString());
        payload.put("analysisReference", analysis.getReference());
        payload.put("status", analysis.getStatus().name());
        payload.put("errorCode", code);
        payload.put("errorMessage", message);
        payload.put("attempts", analysis.getAttemptCount());
        payload.put("model", analysis.getModelName());
        return payload;
    }

    private void broadcastStart(AiAnalysisEntity analysis, IncidentEntity incident) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("analysisId", analysis.getId().toString());
        payload.put("analysisReference", analysis.getReference());
        payload.put("status", AnalysisStatus.RUNNING.name());
        payload.put("model", analysis.getModelName());
        publishAfterCommit(RealtimeEventType.AI_ANALYSIS_STARTED, incident, analysis, payload);
    }

    private void publishAfterCommit(RealtimeEventType type, IncidentEntity incident, AiAnalysisEntity analysis,
                                    Map<String, Object> payload) {
        AfterCommitExecutor.run(() -> {
            publisher.publishFeed(type, incident.getId(), incident.getReference(),
                    incident.getTimelineSeq(), payload);
            publisher.publishIncident(type, incident.getId(), incident.getReference(),
                    incident.getTimelineSeq(), payload);
            if (analysis.getRequestedBy() != null) {
                // Whoever asked for this should learn the outcome without watching the feed.
                publisher.notifyUser(analysis.getRequestedBy(), type, incident.getId(),
                        incident.getReference(), incident.getTimelineSeq(), payload);
            }
        });
    }

    private int maxAttempts() {
        return Math.max(1, properties.maxAttempts());
    }

    private record Claimed(AiAnalysisEntity analysis, IncidentEntity incident) {
    }
}