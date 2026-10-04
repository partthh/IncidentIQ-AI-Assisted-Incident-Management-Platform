package com.sentinelai.realtime;

/** Event types a client can receive. Mirrors the realtime envelope contract. */
public enum RealtimeEventType {

    INCIDENT_CREATED,
    INCIDENT_UPDATED,
    INCIDENT_ASSIGNED,
    INCIDENT_ACKNOWLEDGED,
    INCIDENT_INVESTIGATING,
    INCIDENT_RESOLVED,
    AI_ANALYSIS_STARTED,
    AI_ANALYSIS_COMPLETED,
    AI_ANALYSIS_FAILED,
    TIMELINE_ENTRY_ADDED
}
