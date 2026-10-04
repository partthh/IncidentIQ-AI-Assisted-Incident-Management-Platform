package com.sentinelai.investigation;

import com.sentinelai.common.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read models for AI investigations.
 *
 * <p>The raw model response is deliberately absent from {@link Detail}. It is
 * retained on the row for auditing, but exposing it in the default view would put
 * unvalidated text next to validated text in the same response, and a reviewer
 * skimming the page could not tell which was which.
 */
public final class AnalysisViews {

    private AnalysisViews() {
    }

    /** Feed row: enough to show that work happened and whether it succeeded. */
    public record Summary(
            UUID id,
            String reference,
            UUID incidentId,
            String incidentReference,
            String incidentTitle,
            String service,
            Severity incidentSeverity,
            AnalysisStatus status,
            int attemptCount,
            String triggerReason,
            String modelName,
            String promptVersion,
            Instant createdAt,
            Instant completedAt,
            Long durationMs,
            String errorCode,
            String topHypothesis
    ) {
    }

    /** Full view: the validated analysis plus provenance. */
    public record Detail(
            UUID id,
            String reference,
            UUID incidentId,
            String incidentReference,
            String modelName,
            String promptVersion,
            AnalysisStatus status,
            int attemptCount,
            String triggerReason,
            Map<String, Object> analysis,
            Map<String, Object> usage,
            UUID requestedBy,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            Long durationMs,
            String errorCode,
            String errorMessage
    ) {
    }

    /** Explicit request to investigate, optionally naming who asked. */
    public record InvestigateRequest(String note) {
    }

    /** Acknowledges what the module did and did not do. */
    public record QueueResponse(
            UUID analysisId,
            String reference,
            String incidentReference,
            AnalysisStatus status,
            String triggerReason,
            String message
    ) {
    }

    public static Summary toSummary(AiAnalysisEntity analysis, String incidentReference, String incidentTitle,
                                    String service, Severity incidentSeverity) {
        return new Summary(
                analysis.getId(),
                analysis.getReference(),
                analysis.getIncidentId(),
                incidentReference,
                incidentTitle,
                service,
                incidentSeverity,
                analysis.getStatus(),
                analysis.getAttemptCount() == null ? 0 : analysis.getAttemptCount(),
                analysis.getTriggerReason(),
                analysis.getModelName(),
                analysis.getPromptVersion(),
                analysis.getCreatedAt(),
                analysis.getCompletedAt(),
                analysis.getDurationMs(),
                analysis.getErrorCode(),
                topHypothesis(analysis));
    }

    public static Detail toDetail(AiAnalysisEntity analysis, String incidentReference) {
        return new Detail(
                analysis.getId(),
                analysis.getReference(),
                analysis.getIncidentId(),
                incidentReference,
                analysis.getModelName(),
                analysis.getPromptVersion(),
                analysis.getStatus(),
                analysis.getAttemptCount() == null ? 0 : analysis.getAttemptCount(),
                analysis.getTriggerReason(),
                analysis.getAnalysis(),
                analysis.getUsage(),
                analysis.getRequestedBy(),
                analysis.getCreatedAt(),
                analysis.getStartedAt(),
                analysis.getCompletedAt(),
                analysis.getDurationMs(),
                analysis.getErrorCode(),
                analysis.getErrorMessage());
    }

    /**
     * One-line preview for list rows.
     *
     * <p>Read defensively from the stored JSON rather than re-parsing: the row is the
     * record of what was validated, and a re-parse could in principle differ from it.
     */
    @SuppressWarnings("unchecked")
    private static String topHypothesis(AiAnalysisEntity analysis) {
        if (!AnalysisStatus.COMPLETED.equals(analysis.getStatus()) || analysis.getAnalysis() == null) {
            return null;
        }
        Object hypotheses = analysis.getAnalysis().get("hypotheses");
        if (!(hypotheses instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object first = list.get(0);
        if (!(first instanceof Map<?, ?> map)) {
            return null;
        }
        Object cause = map.get("cause");
        return cause == null ? null : String.valueOf(cause);
    }
}