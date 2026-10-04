package com.sentinelai.detection;

import java.util.regex.Pattern;

/**
 * Reduces a free-text message to a stable signature.
 *
 * <p>Alert fatigue comes from near-duplicates: "Payment declined for order 88213"
 * and "Payment declined for order 99301" are the same defect and should share one
 * incident. Everything volatile is replaced with a placeholder so those two
 * collapse to one signature, while genuinely different messages do not.
 *
 * <p>Deliberately conservative: over-normalising would merge unrelated problems,
 * under-normalising would fragment one problem across many incidents.
 */
public final class ErrorSignatureExtractor {

    /** Maximum signature length; matches the column width. */
    public static final int MAX_LENGTH = 200;

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b");

    private static final Pattern IPV4 = Pattern.compile(
            "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");

    /**
     * Case-insensitive because {@link #extract} lowercases the message before
     * substitution runs; without {@code (?i)} the {@code [T ]} separator never
     * matches {@code t} and timestamps fragment into {@code <num>-<num>-04t10:...}
     * instead of collapsing to a single {@code <ts>}.
     */
    private static final Pattern ISO_TIMESTAMP = Pattern.compile(
            "\\d{4}-\\d{2}-\\d{2}[Tt ]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|[+-]\\d{2}:?\\d{2})?");

    /** Quoted or bracketed values: the most common carrier of ids and paths. */
    private static final Pattern QUOTED = Pattern.compile("\"[^\"]{0,200}\"|'[^']{0,200}'");

    private static final Pattern HEX_ID = Pattern.compile("\\b[0-9a-fA-F]{12,}\\b");

    /**
     * A number carrying a unit suffix: "1800ms", "2.5s", "95 %".
     *
     * <p>This runs before the bare-number patterns because none of them can match
     * digits glued to letters: in {@code 1800ms} there is no word boundary after
     * {@code 1800}, so {@code \b\d{4,}\b} never fires. Without this rule a latency
     * probe sampled every few seconds produces a fresh signature each time and one
     * ongoing outage is fragmented into hundreds of incidents. The unit is kept, not
     * discarded, so "1800ms" and "1800" stay distinguishable and genuinely different
     * quantities still do not merge.
     */
    private static final Pattern NUMBER_WITH_UNIT = Pattern.compile(
            "\\b(\\d+(?:\\.\\d+)?)(\\s?(?:ms|s|m|h|d|%|x|rpm|qps|kib|mib|gib|req/s)\\b)");

    private static final Pattern LONG_DIGITS = Pattern.compile("\\b\\d{4,}\\b");

    private static final Pattern DECIMAL = Pattern.compile("\\b\\d+\\.\\d+\\b");

    private static final Pattern SMALL_NUMBER = Pattern.compile("\\b\\d{1,3}\\b");

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private ErrorSignatureExtractor() {
    }

    /**
     * @param message raw event message, possibly null
     * @return a lowercase signature, never null; blank input yields {@code "unknown"}
     */
    public static String extract(String message) {
        if (message == null || message.isBlank()) {
            return "unknown";
        }
        String signature = message
                .trim()
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\r\\n\\t]+", " ")
                // Longest and most specific patterns first: replacing UUIDs after
                // hex ids would leave fragments behind.
                .replaceAll(UUID.pattern(), "<uuid>")
                .replaceAll(ISO_TIMESTAMP.pattern(), "<ts>")
                .replaceAll(IPV4.pattern(), "<ip>")
                .replaceAll(QUOTED.pattern(), "<val>")
                .replaceAll(HEX_ID.pattern(), "<hex>")
                .replaceAll(NUMBER_WITH_UNIT.pattern(), "<num>$2")
                .replaceAll(LONG_DIGITS.pattern(), "<num>")
                .replaceAll(DECIMAL.pattern(), "<num>")
                .replaceAll(SMALL_NUMBER.pattern(), "<num>")
                .replaceAll(WHITESPACE.pattern(), " ")
                .trim();

        if (signature.isEmpty()) {
            return "unknown";
        }
        return signature.length() <= MAX_LENGTH ? signature : signature.substring(0, MAX_LENGTH);
    }

    /**
     * Builds a signature from an event's structured metadata when the producer
     * supplied one. A dependency name is a far better grouping key than a
     * templated message, so it is preferred when present.
     */
    public static String fromMetadata(String message, java.util.Map<String, Object> metadata) {
        if (metadata != null) {
            Object dependency = metadata.get("dependency");
            if (dependency instanceof String name && !name.isBlank()) {
                return truncate("dependency:" + name.trim().toLowerCase(java.util.Locale.ROOT));
            }
            Object exception = metadata.get("exceptionClass");
            if (exception instanceof String clazz && !clazz.isBlank()) {
                return truncate("exception:" + clazz.trim());
            }
            Object endpoint = metadata.get("endpoint");
            if (endpoint instanceof String path && !path.isBlank()) {
                return truncate("endpoint:" + path.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return extract(message);
    }

    private static String truncate(String value) {
        return value.length() <= MAX_LENGTH ? value : value.substring(0, MAX_LENGTH);
    }
}
