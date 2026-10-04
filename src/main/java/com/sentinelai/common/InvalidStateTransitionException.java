package com.sentinelai.common;

/**
 * An incident state transition was requested that the lifecycle does not allow
 * (HTTP 409). Carrying the legal set in the message makes the API
 * self-describing for clients.
 */
public class InvalidStateTransitionException extends RuntimeException {

    private final String from;
    private final String to;

    public InvalidStateTransitionException(String from, String to, String legalTargets) {
        super("Cannot move incident from " + from + " to " + to + ". Allowed from " + from + ": " + legalTargets);
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public String getTo() {
        return to;
    }
}
