package com.sentinelai.investigation.llm;

/**
 * A provider failure, classified so the job processor can decide whether a retry
 * could plausibly help.
 */
public class LlmException extends RuntimeException {

    private final Kind kind;
    private final int httpStatus;
    private final String rawResponse;

    public LlmException(Kind kind, String message, int httpStatus, Throwable cause) {
        this(kind, message, httpStatus, cause, null);
    }

    public LlmException(Kind kind, String message, int httpStatus, Throwable cause, String rawResponse) {
        super(message, cause);
        this.kind = kind;
        this.httpStatus = httpStatus;
        this.rawResponse = rawResponse;
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
     * Whatever the provider actually sent, when it sent something.
     *
     * <p>Only meaningful for {@link Kind#MALFORMED_RESPONSE}, where the failure is a
     * property of the reply rather than of the transport. It is carried on the
     * exception so the analysis row can record the unreadable text: a FAILED analysis
     * with no record of what arrived is indistinguishable from a bug, and the operator
     * who needs to judge the model — or the validator — has nothing to look at.
     */
    public String getRawResponse() {
        return rawResponse;
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
