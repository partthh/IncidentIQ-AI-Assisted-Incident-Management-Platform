package com.sentinelai.common;

import java.util.UUID;
import org.slf4j.MDC;

/**
 * Per-request correlation id, propagated into logs and error responses so a
 * user-reported failure can be traced end to end.
 */
public final class TraceContext {

    public static final String TRACE_ID = "traceId";
    public static final String USER_ID = "userId";

    private TraceContext() {
    }

    public static String currentTraceId() {
        String id = MDC.get(TRACE_ID);
        return id == null ? UUID.randomUUID().toString() : id;
    }

    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank() && traceId.length() <= 64) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    public static void setUserId(String userId) {
        if (userId != null && !userId.isBlank()) {
            MDC.put(USER_ID, userId);
        }
    }

    public static void clear() {
        MDC.remove(TRACE_ID);
        MDC.remove(USER_ID);
    }
}
