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