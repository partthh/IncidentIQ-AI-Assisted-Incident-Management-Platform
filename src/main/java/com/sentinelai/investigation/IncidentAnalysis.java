package com.sentinelai.investigation;

import java.util.List;

/**
 * The structured shape the model is asked to return.
 *
 * <p>Field names and semantics match the documented contract exactly, so a
 * provider change cannot silently alter what "a hypothesis" means.
 *
 * <p>{@code confidence} is the model's own estimate. It is not a calibrated
 * probability and must never be rendered as one — see
 * {@link AnalysisResponseValidator} for what is actually checked.
 */
public record IncidentAnalysis(
        String summary,
        List<Hypothesis> hypotheses,
        List<String> missingEvidence,
        List<String> caveats
) {

    public record Hypothesis(
            String cause,
            double confidence,
            List<String> evidence,
            List<String> nextChecks,
            List<String> evidenceEventIds
    ) {
    }

    public static IncidentAnalysis of(String summary, List<Hypothesis> hypotheses, List<String> missingEvidence,
                                      List<String> caveats) {
        return new IncidentAnalysis(summary, hypotheses, missingEvidence, caveats);
    }
}
