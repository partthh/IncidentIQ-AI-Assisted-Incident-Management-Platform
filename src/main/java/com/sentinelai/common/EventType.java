package com.sentinelai.common;

/** Kinds of telemetry SentinelAI ingests. */
public enum EventType {

    LOG,
    METRIC,
    HEALTH,
    LATENCY_SPIKE,
    ERROR_RATE_SPIKE,
    SATURATION,
    DEPENDENCY_FAILURE,
    DEPLOY;

    /**
     * Types that represent a metric-style reading rather than free text. Only
     * these are candidates for numeric threshold rules.
     */
    public boolean isMetricLike() {
        return this == METRIC || this == LATENCY_SPIKE || this == ERROR_RATE_SPIKE || this == SATURATION;
    }
}
