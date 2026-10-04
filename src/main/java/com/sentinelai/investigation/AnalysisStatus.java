package com.sentinelai.investigation;

/**
 * Lifecycle of a single AI investigation attempt.
 *
 * <p>{@link #QUEUED} rows are durable work items. A separate poller claims them,
 * which means a crash mid-analysis loses nothing: the row survives and is
 * picked up again (up to {@code maxAttempts}).
 */
public enum AnalysisStatus {

    /** Persisted, not yet claimed by a worker. */
    QUEUED,
    RUNNING,
    COMPLETED,
    /** Provider failed or timed out after exhausting retries. */
    FAILED,
    /** Provider replied, but the response failed schema/semantic validation. */
    REJECTED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == REJECTED;
    }

    public boolean isPending() {
        return this == QUEUED || this == RUNNING;
    }
}
