package com.sentinelai.common;

import java.util.Locale;
import java.util.Optional;

/**
 * Severity with an explicit rank, because detection frequently needs to decide
 * whether an incoming signal should <em>raise</em> the severity of an incident
 * (monotonic escalation) or merely refresh it.
 */
public enum Severity {

    INFO(0),
    LOW(1),
    MEDIUM(2),
    HIGH(3),
    CRITICAL(4);

    private final int rank;

    Severity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }

    public boolean isAtLeast(Severity other) {
        return this.rank >= other.rank;
    }

    public static Optional<Severity> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(raw.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
