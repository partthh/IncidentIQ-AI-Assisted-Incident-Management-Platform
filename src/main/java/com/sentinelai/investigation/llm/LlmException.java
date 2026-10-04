package com.sentinelai.investigation.llm;

/**
 * A provider failure, classified so the job processor can decide whether a retry
 * could plausibly help.
 */
public class LlmException extends RuntimeException {

    private final Kind kind;
    private final int httpStatus;

    public LlmException(Kind kind, String message, int httpStatus, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
    }

    public LlmException(Kind kind, String message) {
        this(kind, message, 0, null);
    }

    /** Convenience for transport failures, which carry a cause but no HTTP status. */
    public LlmException(Kind kind, String message, Throwable cause) {
        this(kind, message, 0, cause);
    }

    public Kind getKind() {
        return kind;
    }

    public int getHttpStatus() {
        return httpStatus;
    }

    /**
     * Whether retrying the identical request could plausibly succeed.
     *
     * <p>A 401 will never fix itself, and neither will a malformed prompt, so both
     * are terminal. Retrying those just burns the attempt budget and delays the
     * FAILED status an operator is waiting to see.
     */
    public boolean isRetryable() {
        return switch (kind) {
            case TIMEOUT, RATE_LIMITED, SERVER_ERROR, CONNECTION -> true;
            case UNAUTHORIZED, BAD_REQUEST, MALFORMED_RESPONSE -> false;
        };
    }

    public enum Kind {
        TIMEOUT,
        RATE_LIMITED,
        SERVER_ERROR,
        CONNECTION,
        UNAUTHORIZED,
        BAD_REQUEST,
        MALFORMED_RESPONSE
    }
}
