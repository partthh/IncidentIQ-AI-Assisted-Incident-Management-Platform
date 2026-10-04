package com.sentinelai.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The sanitizer is the last chance to stop a credential reaching the database and,
 * from there, an external model provider. The tests are written to two rules:
 *
 * <ul>
 *   <li>A false negative sends a real secret to a third party — unacceptable.</li>
 *   <li>A false positive corrupts the evidence an engineer reads — also bad, which is
 *       why the metric fields that detection depends on are explicitly pinned as
 *       untouched.</li>
 * </ul>
 */
class PayloadSanitizerTest {

    @ParameterizedTest
    @MethodSource("credentialSamples")
    void redactsCredentials(String input) {
        String result = PayloadSanitizer.sanitize(input);

        assertThat(result).contains(PayloadSanitizer.REDACTED);
        // The secret fragments below must not survive anywhere in the output.
        assertThat(result).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
        assertThat(result).doesNotContain("hunter2secretvalue");
        assertThat(result).doesNotContain("sk-proj-ABCDEFGHIJKLMNOPQRSTUVWX");
        assertThat(result).doesNotContain("ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345");
        assertThat(result).doesNotContain("abc123def456ghi");
    }

    /**
     * Explicit strings rather than {@code @CsvSource}: several of these begin with a
     * double quote, which a CSV reader would treat as the start of a quoted field and
     * silently truncate.
     */
    static Stream<Arguments> credentialSamples() {
        return Stream.of(
                Arguments.of("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abcdefghijkl"),
                Arguments.of("password=hunter2secretvalue"),
                Arguments.of("api_key: sk-proj-ABCDEFGHIJKLMNOPQRSTUVWX"),
                Arguments.of("\"client_secret\": \"abc123def456ghi\""),
                Arguments.of("token = ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345"));
    }

    @Test
    void redactsAwsAccessKeyIds() {
        assertThat(PayloadSanitizer.sanitize("using AKIAIOSFODNN7EXAMPLE now"))
                .doesNotContain("AKIAIOSFODNN7EXAMPLE")
                .contains(PayloadSanitizer.REDACTED);
    }

    @Test
    void redactsBareJwts() {
        String jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U";

        assertThat(PayloadSanitizer.sanitize("token was " + jwt + " in the header"))
                .doesNotContain(jwt)
                .contains(PayloadSanitizer.REDACTED);
    }

    @Test
    void masksCardNumbersButKeepsTheLastFour() {
        // The last four is what makes a payment log line actionable for support.
        String result = PayloadSanitizer.sanitize("card 4111111111111111 declined");

        assertThat(result).doesNotContain("4111111111111111");
        assertThat(result).contains("1111");
    }

    @Test
    void masksCardNumbersWrittenWithSpaces() {
        String result = PayloadSanitizer.sanitize("card 4111 1111 1111 1111 declined");

        assertThat(result).doesNotContain("4111 1111 1111 1111");
    }

    @Test
    void masksTheLocalPartOfAnEmailButKeepsTheDomain() {
        String result = PayloadSanitizer.sanitize("user alice.smith@example.com not found");

        assertThat(result).doesNotContain("alice.smith@");
        assertThat(result).contains("a***@example.com");
    }

    @Test
    void leavesOrdinaryDiagnosticTextAlone() {
        // Redaction that over-reaches destroys the evidence. An engineer debugging a
        // timeout needs the numbers and the stack frame intact.
        String message = "Connection refused to payments-db:5432 after 3 retries in 1500ms "
                + "at com.sentinelai.PaymentClient.charge(PaymentClient.java:88)";

        assertThat(PayloadSanitizer.sanitize(message)).isEqualTo(message);
    }

    @Test
    void nullAndEmptyArePassedThrough() {
        assertThat(PayloadSanitizer.sanitize((String) null)).isNull();
        assertThat(PayloadSanitizer.sanitize("")).isEmpty();
    }

    @Test
    void replacementIsNotConfusedByDollarSignsInTheInput() {
        // Attacker-influenced text reaches the sanitizer. A String.replaceAll-based
        // implementation would expand "$1" or throw on "\$" here.
        String injected = "error: $1 $0 ${x} \\n password=$1supersecretvalue";

        String result = PayloadSanitizer.sanitize(injected);

        assertThat(result).doesNotContain("supersecretvalue");
    }

    // -------------------------------------------------------------- metadata

    @Test
    void sensitiveKeysAreRedactedWholesale() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dbPassword", "hunter2");
        metadata.put("AUTHORIZATION", "Bearer abcdefghijklmnop");
        metadata.put("userPassword", "anything");
        metadata.put("sessionid", "s-12345");

        Map<String, Object> cleaned = PayloadSanitizer.sanitize(metadata);

        assertThat(cleaned).containsEntry("dbPassword", PayloadSanitizer.REDACTED);
        assertThat(cleaned).containsEntry("AUTHORIZATION", PayloadSanitizer.REDACTED);
        assertThat(cleaned).containsEntry("userPassword", PayloadSanitizer.REDACTED);
        assertThat(cleaned).containsEntry("sessionid", PayloadSanitizer.REDACTED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"password", "Password", "apiKey", "api_key", "API-KEY", "clientSecret",
            "credential", "cookie", "privateKey", "connectionString", "accessToken"})
    void recognisesSensitiveKeysRegardlessOfCasingAndSeparators(String key) {
        assertThat(PayloadSanitizer.isSensitiveKey(key)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"p95LatencyMs", "errorRate", "cpuUtilization", "poolUtilization",
            "consecutiveFailures", "statusCode", "endpoint", "dependency", "traceId"})
    void leavesNonSensitiveKeysAlone(String key) {
        assertThat(PayloadSanitizer.isSensitiveKey(key)).isFalse();
    }

    @Test
    void numericMetricsSurviveUntouched() {
        // Detection reads these numbers. Coercing or rounding them would silently
        // change what fires, and the engineer investigating would be looking at
        // evidence the rules never saw.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("p95LatencyMs", 1500);
        metadata.put("errorRate", 0.073);
        metadata.put("poolUtilization", 0.97);
        metadata.put("consecutiveFailures", 4L);

        assertThat(PayloadSanitizer.sanitize(metadata))
                .containsEntry("p95LatencyMs", 1500)
                .containsEntry("errorRate", 0.073)
                .containsEntry("poolUtilization", 0.97)
                .containsEntry("consecutiveFailures", 4L);
    }

    @Test
    void booleansSurviveUntouched() {
        Map<String, Object> metadata = Map.of("degraded", true, "healthy", false);

        assertThat(PayloadSanitizer.sanitize(metadata))
                .containsEntry("degraded", true)
                .containsEntry("healthy", false);
    }

    @Test
    void nonSensitiveStringValuesAreStillScannedForEmbeddedSecrets() {
        Map<String, Object> metadata = Map.of("message", "failed with token=abcdefghijklmnopqrs");

        assertThat(PayloadSanitizer.sanitize(metadata).get("message"))
                .asString().doesNotContain("abcdefghijklmnopqrs");
    }

    @Test
    void nestedMapsAreSanitizedRecursively() {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put("password", "hunter2");
        inner.put("host", "db.internal");

        Map<String, Object> outer = new LinkedHashMap<>();
        outer.put("connection", inner);

        Map<String, Object> cleaned = PayloadSanitizer.sanitize(outer);

        @SuppressWarnings("unchecked")
        Map<String, Object> cleanedInner = (Map<String, Object>) cleaned.get("connection");
        assertThat(cleanedInner).containsEntry("password", PayloadSanitizer.REDACTED);
        assertThat(cleanedInner).containsEntry("host", "db.internal");
    }

    @Test
    void listsAreSanitizedRecursively() {
        Map<String, Object> metadata = Map.of("attempts", List.of(
                Map.of("url", "https://x?api_key=sk-ABCDEFGHIJKLMNOPQRSTUVWX"),
                "plain text"));

        @SuppressWarnings("unchecked")
        List<Object> attempts = (List<Object>) PayloadSanitizer.sanitize(metadata).get("attempts");

        assertThat(attempts).hasSize(2);
        assertThat(attempts.get(0).toString()).doesNotContain("sk-ABCDEFGHIJKLMNOPQRSTUVWX");
        assertThat(attempts.get(1)).isEqualTo("plain text");
    }

    @Test
    void nullMetadataBecomesAnEmptyMapRatherThanNull() {
        // A null here would surface as a NullPointerException in the caller, far from
        // the cause, so the empty result is the friendlier contract.
        assertThat(PayloadSanitizer.sanitize((Map<String, Object>) null)).isEmpty();
        assertThat(PayloadSanitizer.sanitize(Map.of())).isEmpty();
    }

    @Test
    void nullKeysAreDropped() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(null, "orphan");
        metadata.put("kept", "value");

        assertThat(PayloadSanitizer.sanitize(metadata)).containsOnlyKeys("kept");
    }

    @Test
    void nullValuesArePreserved() {
        // Some producers send explicit nulls; dropping the key would change the shape
        // of the evidence an engineer reads.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("previousError", null);

        assertThat(PayloadSanitizer.sanitize(metadata)).containsEntry("previousError", null);
    }
}