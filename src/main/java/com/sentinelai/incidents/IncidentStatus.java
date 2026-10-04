package com.sentinelai.incidents;

/**
 * Incident lifecycle.
 *
 * <p>States that are not {@link #RESOLVED} are considered <em>active</em>: an
 * active incident with the same fingerprint absorbs further occurrences instead
 * of spawning duplicates.
 */
public enum IncidentStatus {

    OPEN,
    ACKNOWLEDGED,
    INVESTIGATING,
    RESOLVED;

    public boolean isActive() {
        return this != RESOLVED;
    }

    public boolean isTerminal() {
        return this == RESOLVED;
    }
}
