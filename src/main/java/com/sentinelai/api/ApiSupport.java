package com.sentinelai.api;

import com.sentinelai.common.ConflictException;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Request-parsing rules shared by every list endpoint.
 *
 * <p>Centralised because each of these is a place where an unguarded value from a
 * query string becomes either a 500 or a full table scan:
 * <ul>
 *   <li>Page size is clamped. An unbounded {@code size} is both a trivially available
 *       denial of service and an accidental megabyte of JSON for one request.</li>
 *   <li>Sort fields are allow-listed. Passing arbitrary property names into
 *       {@link Sort} lets a caller sort by an unindexed column, or by one that does
 *       not exist, and get an unhelpful database error either way.</li>
 *   <li>Version preconditions come from either the body or {@code If-Match}, so a
 *       client using conditional requests does not have to know a body convention.</li>
 * </ul>
 */
public final class ApiSupport {

    public static final int DEFAULT_PAGE_SIZE = 25;
    public static final int MAX_PAGE_SIZE = 200;

    private ApiSupport() {
    }

    public static Pageable pageable(int page, Integer size) {
        int safeSize = size == null ? DEFAULT_PAGE_SIZE : Math.clamp(size, 1, MAX_PAGE_SIZE);
        int safePage = Math.max(0, page);
        return PageRequest.of(safePage, safeSize);
    }

    /**
     * Builds a sort, ignoring anything outside {@code allowed}.
     *
     * <p>Unknown properties are dropped rather than rejected: sorting is a hint about
     * presentation, and failing a whole request because the client asked for an
     * unusual ordering would be a poor trade.
     */
    public static Sort sort(String property, boolean ascending, Set<String> allowed, Sort fallback) {
        if (property == null || property.isBlank() || !allowed.contains(property)) {
            return fallback;
        }
        return Sort.by(ascending ? Sort.Direction.ASC : Sort.Direction.DESC, property);
    }

    /**
     * Resolves the caller's expected version.
     *
     * <p>An explicit body value wins over the header. Returning {@code null} means
     * "no precondition", which the services treat as last-write-wins — legitimate for
     * a note, risky for a reassignment, so clients are expected to send it.
     */
    public static Long expectedVersion(Long bodyVersion, String ifMatchHeader) {
        if (bodyVersion != null) {
            return bodyVersion;
        }
        if (ifMatchHeader == null || ifMatchHeader.isBlank()) {
            return null;
        }
        String candidate = ifMatchHeader.trim();
        if (candidate.startsWith("W/")) {
            candidate = candidate.substring(2);
        }
        candidate = candidate.replace("\"", "").trim();
        if (candidate.isEmpty() || "*".equals(candidate)) {
            return null;
        }
        try {
            return Long.parseLong(candidate);
        } catch (NumberFormatException ex) {
            throw new ConflictException("If-Match must carry a numeric incident version, got '"
                    + ifMatchHeader + "'");
        }
    }

    /** Lower-cases and trims a filter value, treating blank as absent. */
    public static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}