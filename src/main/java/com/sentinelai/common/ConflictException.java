package com.sentinelai.common;

/** Request conflicts with current server state (HTTP 409). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
