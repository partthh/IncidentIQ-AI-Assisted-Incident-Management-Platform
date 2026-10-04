package com.sentinelai.investigation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Persisted record of one AI investigation: what was asked, under which model
 * and prompt version, against which evidence snapshot, and what came back.
 *
 * <p>The evidence snapshot is stored so that a hypothesis can be re-checked
 * later even if the underlying events are pruned or the prompt changes.
 */
@Entity
@Table(name = "ai_analyses")
public class AiAnalysisEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 40)
    private String reference;

    @Column(name = "incident_id", nullable = false)
    private UUID incidentId;

    @Column(name = "model_name", nullable = false, length = 120)
    private String modelName;

    @Column(name = "prompt_version", nullable = false, length = 40)
    private String promptVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AnalysisStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    @Column(name = "trigger_reason", length = 60)
    private String triggerReason;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "analysis_json", columnDefinition = "jsonb")
    private Map<String, Object> analysis;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "evidence_snapshot_json", columnDefinition = "jsonb")
    private Map<String, Object> evidenceSnapshot;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "usage_json", columnDefinition = "jsonb")
    private Map<String, Object> usage;

    @Column(name = "requested_by")
    private UUID requestedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "duration_ms")
    private Long durationMs;

    /**
     * Verbatim reply from the model, retained even when the response was rejected.
     * A rejection nobody can inspect is indistinguishable from a bug in the
     * validator, and re-running the prompt may well produce a different — and
     * equally unexamined — answer.
     */
    @Column(name = "raw_response", columnDefinition = "text")
    private String rawResponse;

    @Column(name = "error_code", length = 60)
    private String errorCode;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    protected AiAnalysisEntity() {
        // for JPA
    }

    public AiAnalysisEntity(UUID id, String reference, UUID incidentId, String modelName, String promptVersion,
                            AnalysisStatus status, String triggerReason, UUID requestedBy, Instant createdAt) {
        this.id = id;
        this.reference = reference;
        this.incidentId = incidentId;
        this.modelName = modelName;
        this.promptVersion = promptVersion;
        this.status = status;
        this.triggerReason = triggerReason;
        this.requestedBy = requestedBy;
        this.createdAt = createdAt;
        this.attemptCount = 0;
    }

    public void markRunning(Instant at) {
        this.status = AnalysisStatus.RUNNING;
        this.startedAt = at;
        this.attemptCount = this.attemptCount + 1;
    }

    /**
     * Returns the job to the queue, recording why.
     *
     * <p>The reason is kept rather than cleared so an operator staring at a stalled
     * queue can see what the last attempt did, and so the timeline of a long-lived
     * investigation is readable without digging through provider logs.
     */
    public void markRequeued(String reasonCode, String reasonMessage) {
        this.status = AnalysisStatus.QUEUED;
        this.startedAt = null;
        this.errorCode = reasonCode;
        this.errorMessage = reasonMessage == null ? null
                : reasonMessage.substring(0, Math.min(999, reasonMessage.length()));
    }

    public void markCompleted(Map<String, Object> analysis, Map<String, Object> evidence,
                           Map<String, Object> usage, String rawResponse, Instant at) {
        this.status = AnalysisStatus.COMPLETED;
        this.analysis = analysis == null ? null : new LinkedHashMap<>(analysis);
        this.evidenceSnapshot = evidence == null ? null : new LinkedHashMap<>(evidence);
        this.usage = usage;
        this.rawResponse = rawResponse;
        this.completedAt = at;
        this.durationMs = startedAt == null ? null : at.toEpochMilli() - startedAt.toEpochMilli();
        this.errorCode = null;
        this.errorMessage = null;
    }

    /**
     * Records a terminal failure. The raw response is preserved here too: a
     * {@code REJECTED} analysis with no record of what the model actually said
     * cannot be distinguished from a bug in the validator.
     */
    public void markFailed(AnalysisStatus terminal, String errorCode, String errorMessage, String rawResponse,
                           Instant at) {
        this.status = terminal;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage == null ? null
                : errorMessage.substring(0, Math.min(999, errorMessage.length()));
        if (rawResponse != null) {
            this.rawResponse = rawResponse;
        }
        this.completedAt = at;
        this.durationMs = startedAt == null ? null : at.toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * Stamps an attempt outcome as soon as the provider answers, before validation.
     * Persisted separately so a process that dies mid-validation still leaves the
     * model's reply on the record rather than losing it entirely.
     */
    public void recordRawResponse(String rawResponse) {
        if (rawResponse != null) {
            this.rawResponse = rawResponse;
        }
    }

    public UUID getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public UUID getIncidentId() {
        return incidentId;
    }

    public String getModelName() {
        return modelName;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public AnalysisStatus getStatus() {
        return status;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public String getTriggerReason() {
        return triggerReason;
    }

    public Map<String, Object> getAnalysis() {
        return analysis;
    }

    public Map<String, Object> getEvidenceSnapshot() {
        return evidenceSnapshot;
    }

    public Map<String, Object> getUsage() {
        return usage;
    }

    public UUID getRequestedBy() {
        return requestedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public String getRawResponse() {
        return rawResponse;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
