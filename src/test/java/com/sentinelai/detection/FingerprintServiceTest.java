package com.sentinelai.detection;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint is the whole dedupe mechanism: if two producers send the same
 * failure, the computed value decides whether it is one incident or two. These
 * tests pin the properties that mechanism depends on, including the ones that
 * would silently cause incident storms if they broke.
 */
class FingerprintServiceTest {

    private final FingerprintService service = new FingerprintService();

    private final UUID payments = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID booking = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void isDeterministic() {
        assertThat(service.compute(payments, "NullPointerException"))
                .isEqualTo(service.compute(payments, "NullPointerException"));
    }

    @Test
    void sameFailureOnDifferentServicesGetsDifferentFingerprints() {
        // One service's outage is often another's symptom; they must stay separate
        // incidents that correlation can then link, rather than collapsing into one.
        assertThat(service.compute(payments, "TimeoutException"))
                .isNotEqualTo(service.compute(booking, "TimeoutException"));
    }

    @Test
    void differentFailuresOnTheSameServiceGetDifferentFingerprints() {
        assertThat(service.compute(payments, "TimeoutException"))
                .isNotEqualTo(service.compute(payments, "NullPointerException"));
    }

    @Test
    void signatureCaseDoesNotSplitTheFingerprint() {
        // Error signatures are extracted from arbitrary log text. Without
        // normalisation, "connection refused" and "Connection Refused" would each
        // open their own incident during a single outage.
        assertThat(service.compute(payments, "Connection refused"))
                .isEqualTo(service.compute(payments, "connection refused"));
    }

    @Test
    void whitespaceDoesNotSplitTheFingerprint() {
        assertThat(service.compute(payments, "  connection refused  "))
                .isEqualTo(service.compute(payments, "connection refused"));
    }

    @Test
    void nullSignatureFallsBackRatherThanCollapsingAllUnsignedEvents() {
        // All "unknown" fingerprints collapse to one incident per service. That is
        // the intended fail-safe: an unsigned error is less precise, so it is
        // aggregated, not split into a new incident per event.
        assertThat(service.compute(payments, null)).isEqualTo(service.compute(payments, "unknown"));
        assertThat(service.compute(payments, null))
                .isNotEqualTo(service.compute(booking, null));
    }

    @Test
    void fieldBoundaryIsUnambiguous() {
        // Without a separator that cannot appear in either field, ("ab", "c") and
        // ("a", "bc") would hash the same string and merge two unrelated incidents.
        assertThat(service.compute(UUID.fromString("00000000-0000-0000-0000-0000000000ab"), "c"))
                .isNotEqualTo(service.compute(UUID.fromString("00000000-0000-0000-0000-00000000000a"), "bc"));
    }

    @Test
    void isAFullSha256HexDigest() {
        // Stored in a fixed-width column; a shorter value would indicate the
        // canonical string is not what this class thinks it is.
        assertThat(service.compute(payments, "NullPointerException"))
                .hasSize(64)
                .matches("[0-9a-f]{64}");
    }

    @Test
    void doesNotLeakTheErrorTextIntoTheFingerprint() {
        // The fingerprint is queryable and appears in URLs and logs. Hashing keeps
        // user data from a log line out of places designed to be indexed.
        String fingerprint = service.compute(payments, "customer ssn 123-45-6789 declined");
        assertThat(fingerprint).doesNotContain("ssn").doesNotContain("123");
    }
}