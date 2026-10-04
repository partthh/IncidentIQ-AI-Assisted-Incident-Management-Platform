package com.sentinelai.detection;

import java.util.List;
import java.util.UUID;

/**
 * What detection decided to do with an event, and why.
 *
 * <p>Returned by the ingestion endpoint so a producer can see, without a follow-up
 * request, whether its alert caused an incident, merely refreshed one, or was
 * correlated into a downstream incident.
 */
public record DetectionOutcome(
        boolean triggered,
        UUID incidentId,
        String incidentReference,
        String action,
        List<String> matchedRuleCodes
) {

    public static DetectionOutcome none(List<String> matchedRuleCodes) {
        return new DetectionOutcome(false, null, null, "NO_MATCH", matchedRuleCodes);
    }

    /** A new incident was opened by this event. */
    public static final String ACTION_OPENED = "INCIDENT_OPENED";

    /** An active incident's counters were refreshed. */
    public static final String ACTION_OCCURRENCE = "OCCURRENCE_RECORDED";

    /** The event was attached to another service's open incident as a symptom. */
    public static final String ACTION_CORRELATED = "SYMPTOM_CORRELATED";

    /** Counted, but throttled: inside the rule's dedupe window. */
    public static final String ACTION_SUPPRESSED = "THROTTLED_BY_DEDUPE_WINDOW";

    /** A rule matched but the per-service active incident limit was reached. */
    public static final String ACTION_THROTTLED = "SUPPRESSED_ACTIVE_LIMIT";
}
