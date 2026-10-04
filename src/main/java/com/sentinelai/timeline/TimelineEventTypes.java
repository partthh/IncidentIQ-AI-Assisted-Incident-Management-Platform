package com.sentinelai.timeline;

/** Canonical {@code event_type} values written to {@code incident_timeline}. */
public final class TimelineEventTypes {

    public static final String CREATED = "INCIDENT_CREATED";
    public static final String OCCURRENCE = "OCCURRENCE_RECORDED";
    public static final String CORRELATED = "SYMPTOM_CORRELATED";
    public static final String ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String INVESTIGATING = "INVESTIGATION_STARTED";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String UNASSIGNED = "UNASSIGNED";
    public static final String NOTE = "NOTE";
    public static final String RESOLVED = "RESOLVED";
    public static final String AI_QUEUED = "AI_ANALYSIS_QUEUED";
    public static final String AI_COMPLETED = "AI_ANALYSIS_COMPLETED";
    public static final String AI_FAILED = "AI_ANALYSIS_FAILED";
    public static final String AI_REJECTED = "AI_ANALYSIS_REJECTED";
    public static final String SEVERITY_ESCALATED = "SEVERITY_ESCALATED";

    private TimelineEventTypes() {
    }
}
