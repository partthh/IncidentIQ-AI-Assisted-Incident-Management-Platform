package com.sentinelai.ingestion;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Strips credentials and unnecessary personal data from telemetry before it is
 * stored or sent to a model.
 *
 * <p>Two reasons this runs at <em>ingestion</em> rather than only at the LLM
 * boundary:
 * <ul>
 *   <li>Secrets never enter the database, so a backup or a careless query cannot
 *       leak them.</li>
 *   <li>Redacted evidence is what gets stored, so every downstream reader — the
 *       dashboard, an audit export, a support ticket — sees the same safe text.</li>
 * </ul>
 *
 * <p>This is a safety net, not a guarantee. Untrusted log text is still treated
 * as data rather than instructions elsewhere; see the AI prompt builder.
 */
public final class PayloadSanitizer {

    public static final String REDACTED = "[redacted]";

    /**
     * Metadata keys whose values are replaced wholesale. Pattern matching cannot
     * be relied on to catch every shape of secret, so a matching key is enough.
     */
    private static final List<String> SENSITIVE_KEY_HINTS = List.of(
            "password", "passwd", "pwd", "secret", "token", "apikey",
            "authorization", "auth", "credential", "sessionid", "cookie",
            "privatekey", "clientsecret", "connectionstring");

    private record Rule(Pattern pattern, Function<MatchResult, String> replacement) {
    }

    private static final List<Rule> TEXT_RULES = List.of(
            // Authorization headers.
            new Rule(Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{8,}"),
                    m -> m.group(1) + " " + REDACTED),
            // JSON Web Tokens.
            new Rule(Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{5,}"),
                    m -> REDACTED),
            // Cloud provider access key ids.
            new Rule(Pattern.compile("(?i)\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"), m -> REDACTED),
            // Provider-issued API keys (OpenAI, Stripe, GitHub, Slack).
            new Rule(Pattern.compile("(?i)\\b(?:sk|pk|ghp|xox[baprs])-?[A-Za-z0-9_-]{16,}\\b"), m -> REDACTED),
            // key=value / key: value / "key": "value" forms.
            //
            // The optional quote after the key name is what makes the JSON shape work:
            // log lines routinely carry fragments like "client_secret": "abc123", and
            // without it the pattern cannot reach the separator past the closing quote.
            // The key group stays mandatory — making it optional would match any
            // "host:5432" or "File.java:88" and redact ordinary diagnostic text.
            new Rule(Pattern.compile("(?i)\\b([a-z_]*(?:password|passwd|secret|token|api[_-]?key|"
                            + "access[_-]?key|authorization|credential[a-z_]*)\\b)\"?\\s*[:=]\\s*"
                            + "\"?[^\"',;\\s}]+\"?"),
                    m -> m.group(1) + "=" + REDACTED),
            // Email addresses: keep the domain (often the useful part) but drop the
            // local part, which identifies a person.
            new Rule(Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b"),
                    PayloadSanitizer::maskEmail),
            // Long digit runs: payment card numbers.
            new Rule(Pattern.compile("\\b(?:\\d[ -]?){13,19}\\b"), PayloadSanitizer::maskCard));

    private PayloadSanitizer() {
    }

    public static String sanitize(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Rule rule : TEXT_RULES) {
            Matcher matcher = rule.pattern().matcher(result);
            if (matcher.find()) {
                // Function-based replacement avoids the `$`/`\` escaping rules that
                // apply to string replacements, which matters because matched text is
                // attacker-influenced.
                result = matcher.replaceAll(rule.replacement());
            }
        }
        return result;
    }    /**
     * Copies metadata with sensitive keys redacted and remaining values sanitized.
     * Numbers and booleans are preserved exactly: detection rules read them, and
     * altering a metric would silently break thresholding.
     */
    public static Map<String, Object> sanitize(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            cleaned.put(entry.getKey(), isSensitiveKey(entry.getKey())
                    ? REDACTED
                    : sanitizeValue(entry.getValue()));
        }
        return cleaned;
    }

    public static boolean isSensitiveKey(String key) {
        String normalised = key.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return SENSITIVE_KEY_HINTS.stream().anyMatch(normalised::contains);
    }

    @SuppressWarnings("unchecked")
    private static Object sanitizeValue(Object value) {
        if (value instanceof String text) {
            return sanitize(text);
        }
        if (value instanceof Map<?, ?> nested) {
            Map<String, Object> result = new LinkedHashMap<>();
            nested.forEach((key, nestedValue) -> result.put(String.valueOf(key),
                    isSensitiveKey(String.valueOf(key)) ? REDACTED : sanitizeValue(nestedValue)));
            return result;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(PayloadSanitizer::sanitizeValue).toList();
        }
        return value;
    }

    private static String maskEmail(MatchResult match) {
        String email = match.group();
        int at = email.indexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }

    private static String maskCard(MatchResult match) {
        String digits = match.group().replaceAll("[^0-9]", "");
        int visible = Math.min(4, digits.length());
        return "*".repeat(digits.length() - visible) + digits.substring(digits.length() - visible);
    }
}
