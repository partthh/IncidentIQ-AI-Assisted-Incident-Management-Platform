package com.sentinelai.common;

/** Authenticated but not permitted to perform this action (HTTP 403). */
public class ForbiddenActionException extends RuntimeException {

    public ForbiddenActionException(String message) {
        super(message);
    }
}
