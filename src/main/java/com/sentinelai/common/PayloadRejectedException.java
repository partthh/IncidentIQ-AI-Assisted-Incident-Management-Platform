package com.sentinelai.common;

/** The submitted payload failed validation at the edge (HTTP 422). */
public class PayloadRejectedException extends RuntimeException {

    private final String code;

    public PayloadRejectedException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
