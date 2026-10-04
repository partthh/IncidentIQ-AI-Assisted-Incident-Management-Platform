package com.sentinelai.detection;

import com.sentinelai.common.Severity;

/**
 * A rule that fired for a specific event, together with the evidence that made
 * it fire. Persisted on the incident so an operator can always answer "why was
 * this incident opened, and on what measurement?".
 */
public record RuleMatch(
        String ruleCode,
        String ruleName,
        Severity severity,
        String correlationGroup,
        int dedupeWindowSeconds,
        int windowSeconds,
        Double observedValue,
        Double threshold,
        String observedKey,
        String evidence
) {

    /** Human-readable one-liner stored as the incident's trigger evidence. */
    public String describe() {
        if (observedValue == null) {
            return ruleName + " [" + ruleCode + "]: event type matched with no numeric measurement";
        }
        String formatted = observedValue % 1 == 0
                ? String.valueOf(observedValue.longValue())
                : String.valueOf(observedValue);
        return ruleName + " [" + ruleCode + "]: " + observedKey + "=" + formatted
                + (threshold == null ? "" : " (threshold " + threshold + ")")
                + " — " + evidence;
    }
}
