package com.sentinelai.detection;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Computes the fingerprint that decides when two alerts are "the same problem".
 *
 * <p>The fingerprint is a deterministic hash of (service, error signature). Two
 * design choices are worth stating explicitly:
 *
 * <ul>
 *   <li><b>Signature, not rule.</b> If two rules observe the same symptom they
 *       must not open two incidents. Keying on the signature means the first rule
 *       to fire owns the incident and later rules merely refresh it; the firing
 *       rule is still recorded as data on the incident.</li>
 *   <li><b>Not time-windowed.</b> A deliberate exception to the "dedupe within a
 *       window" rule: an incident that is still active absorbs its own repeats
 *       indefinitely. Time-based expiry would let a long outage fragment into
 *       dozens of incidents, which is precisely the failure this design exists to
 *       prevent.</li>
 * </ul>
 *
 * <p>Correlation across <em>different</em> services is a separate concern handled
 * by {@link CorrelationService}, which attaches downstream symptoms rather than
 * changing any fingerprint.
 */
@Component
public class FingerprintService {

    private static final char SEPARATOR = '|';

    /**
     * Defensive normalisation, even though {@link ErrorSignatureExtractor} already
     * emits a lowercase, trimmed signature.
     *
     * <p>The fingerprint is the dedupe key for the whole system, and an unnormalised
     * input reaching it — a service registered by hand, a metadata-derived signature
     * from a future caller — would open a second incident for the same outage with no
     * error anywhere. Normalising here costs a few microseconds per event and removes
     * that failure mode permanently. The separator cannot appear in a UUID or in a
     * signature produced by the extractor, so the canonical form is unambiguous.
     */
    public String compute(UUID serviceId, String errorSignature) {
        String canonical = serviceId + String.valueOf(SEPARATOR)
                + normalise(errorSignature);
        return sha256Hex(canonical);
    }

    private static String normalise(String errorSignature) {
        if (errorSignature == null || errorSignature.isBlank()) {
            return "unknown";
        }
        return errorSignature.trim().toLowerCase(java.util.Locale.ROOT);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is mandated by the platform; absence means a broken JVM.
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
