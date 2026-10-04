package com.sentinelai.common;

import java.time.Instant;
import java.util.Map;

/**
 * Single error shape for every failure response, so clients never have to guess
 * which envelope a given endpoint uses.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        String traceId,
        Map<String, String> details
) {
}
