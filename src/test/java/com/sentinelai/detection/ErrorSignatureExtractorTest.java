package com.sentinelai.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Signature extraction is the difference between one incident per outage and one
 * incident per log line. Both directions of error are bad and asymmetric:
 * over-normalising hides real distinct faults, under-normalising floods the feed.
 */
class ErrorSignatureExtractorTest {

    @Test
    void volatileIdentifiersCollapseToOneSignature() {
        // The core case: two different order numbers, one defect.
        assertThat(ErrorSignatureExtractor.extract("Payment declined for order 88213"))
                .isEqualTo(ErrorSignatureExtractor.extract("Payment declined for order 99301"));
    }

    @Test
    void differentErrorTypesStayDistinct() {
        assertThat(ErrorSignatureExtractor.extract("Connection refused to payments-db"))
                .isNotEqualTo(ErrorSignatureExtractor.extract("Socket timeout to payments-db"));
    }

    @Test
    void uuipsAndTimestampsAreReplacedNotPartiallyMatched() {
        String a = ErrorSignatureExtractor.extract(
                "Trace 550e8400-e29b-41d4-a716-446655440000 failed at 2026-10-04T10:11:12Z");
        String b = ErrorSignatureExtractor.extract(
                "Trace 6ba7b810-9dad-11d1-80b4-00c04fd430c8 failed at 2026-10-04T10:11:13Z");

        assertThat(a).isEqualTo(b);
        assertThat(a).doesNotContain("550e8400").doesNotContain("2026-10-04T10:11:12");
    }

    @Test
    void ipAddressesAreReplaced() {
        assertThat(ErrorSignatureExtractor.extract("upstream 10.4.2.19 unreachable"))
                .isEqualTo(ErrorSignatureExtractor.extract("upstream 10.4.2.20 unreachable"));
    }

    /**
     * The regression this guards is incident fragmentation, not tidiness: a latency
     * probe reporting a different number every few seconds has no word boundary
     * after its digits, so a bare {@code \b\d{4,}\b} leaves the raw value in place
     * and every sample opens a new incident for one ongoing outage.
     */
    @Test
    void numbersGluedToAUnitCollapseButKeepTheirUnit() {
        String first = ErrorSignatureExtractor.extract("checkout latency p95 1800ms");
        String second = ErrorSignatureExtractor.extract("checkout latency p95 2340ms");

        assertThat(first).isEqualTo(second);
        assertThat(first).doesNotContain("1800").doesNotContain("2340");

        // Same digits, different unit: a different quantity, so it must not merge.
        assertThat(ErrorSignatureExtractor.extract("queue depth 12"))
                .isNotEqualTo(ErrorSignatureExtractor.extract("queue depth 12s"));
        assertThat(ErrorSignatureExtractor.extract("error rate 12.4%"))
                .isEqualTo(ErrorSignatureExtractor.extract("error rate 19.9%"));
        assertThat(ErrorSignatureExtractor.extract("checkout latency p95 1800ms"))
                .isNotEqualTo(ErrorSignatureExtractor.extract("cpu saturation 85%"));
    }

    /** "p95" names a percentile, not a quantity, so it must survive normalisation. */
    @Test
    void metricNamesAreNotMangled() {
        assertThat(ErrorSignatureExtractor.extract("checkout latency p95 1800ms"))
                .contains("p95");
        // A unit must not be borrowed from the start of an unrelated word.
        assertThat(ErrorSignatureExtractor.extract("top 3 services failing"))
                .isEqualTo(ErrorSignatureExtractor.extract("top 9 services failing"));
    }

    @Test
    void quotedValuesAreReplaced() {
        assertThat(ErrorSignatureExtractor.extract("user \"alice@example.com\" not authorised"))
                .isEqualTo(ErrorSignatureExtractor.extract("user \"bob@example.com\" not authorised"));
    }

    @Test
    void caseAndWhitespaceAreNormalised() {
        assertThat(ErrorSignatureExtractor.extract("  CONNECTION   REFUSED \n"))
                .isEqualTo(ErrorSignatureExtractor.extract("connection refused"));
    }

    @Test
    void blankInputBecomesUnknown() {
        assertThat(ErrorSignatureExtractor.extract(null)).isEqualTo("unknown");
        assertThat(ErrorSignatureExtractor.extract("")).isEqualTo("unknown");
        assertThat(ErrorSignatureExtractor.extract("   ")).isEqualTo("unknown");
    }

    @Test
    void signaturesAreBoundedToTheColumnWidth() {
        // The column is 200 characters. A longer signature would be silently
        // truncated by the database with an error, or would collide by sharing a prefix.
        String message = "failure " + "x".repeat(500);
        assertThat(ErrorSignatureExtractor.extract(message)).hasSizeLessThanOrEqualTo(ErrorSignatureExtractor.MAX_LENGTH);
    }

    @Test
    void structuredMetadataIsPreferredOverTheMessage() {
        // A dependency name groups by cause; the templated message does not.
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dependency", "stripe-api");
        metadata.put("exceptionClass", "java.net.SocketTimeoutException");

        assertThat(ErrorSignatureExtractor.fromMetadata("upstream call failed", metadata))
                .isEqualTo("dependency:stripe-api");
    }

    @Test
    void exceptionClassIsUsedWhenNoDependencyIsPresent() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("exceptionClass", "java.net.SocketTimeoutException");

        assertThat(ErrorSignatureExtractor.fromMetadata("upstream call failed", metadata))
                .isEqualTo("exception:java.net.SocketTimeoutException");
    }

    @Test
    void fallsBackToTheMessageWhenMetadataCarriesNothingUseful() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("dependency", "  ");
        metadata.put("unrelated", "value");

        assertThat(ErrorSignatureExtractor.fromMetadata("connection refused", metadata))
                .isEqualTo(ErrorSignatureExtractor.extract("connection refused"));
    }

    @Test
    void nullMetadataIsHandled() {
        assertThat(ErrorSignatureExtractor.fromMetadata("connection refused", null))
                .isEqualTo("connection refused");
    }
}