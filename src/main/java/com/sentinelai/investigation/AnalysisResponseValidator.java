package com.sentinelai.investigation;

import com.sentinelai.investigation.llm.LlmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Gate between untrusted model output and anything the system will act on.
 *
 * <p>A model that speaks JSON can still produce JSON that is wrong, incoherent, or
 * hijacked. This validator rejects a response unless it is structurally sound,
 * internally consistent with the evidence it was given, and free of signs that it
 * followed instructions found <em>inside</em> the logs.
 *
 * <p>Rejecting is always the safe choice: the analysis row is kept with status
 * {@code REJECTED} and the raw response recorded, so a human can inspect what the
 * model tried to say. Silently "fixing" a bad response would put unverified text in
 * front of an engineer making a decision.
 *
 * <p>Stateless and therefore a singleton bean: the rules below are the project's
 * definition of "an analysis is trustworthy", and having exactly one of them means
 * every caller — production, tests, the queue processor — enforces the same bar.
 */
@Component
public class AnalysisResponseValidator {

    /** Generous but finite: an unbounded response is itself a signal. */
    private static final int MAX_SUMMARY_CHARS = 2_000;
    private static final int MAX_CAUSES = 6;
    private static final int MAX_EVIDENCE_PER_HYPOTHESIS = 12;
    private static final int MAX_CHECKS = 10;
    private static final int MAX_MISSING_EVIDENCE = 12;
    private static final int MAX_TEXT_CHARS = 500;

    /**
     * Phrases that only make sense in an instruction to a model. If these appear in
     * a field the <em>model</em> authored, it has very likely restated an injected
     * instruction rather than reported an observation.
     *
     * <p>Note the asymmetry that makes this usable: the same phrases inside the
     * evidence are perfectly normal. A log line containing "ignore previous
     * instructions" is exactly the attack we want preserved as evidence, so this
     * check is applied only to model-authored fields.
     */
    private static final List<Pattern> INJECTION_MARKERS = List.of(
            Pattern.compile("(?i)\\bignore\\s+(?:all\\s+)?(?:the\\s+)?(?:previous|prior|above|earlier)\\b"),
            Pattern.compile("(?i)\\bdisregard\\s+(?:all\\s+)?(?:the\\s+)?(?:previous|prior|above|earlier)\\b"),
            Pattern.compile("(?i)\\byou\\s+are\\s+now\\b"),
            Pattern.compile("(?i)\\bnew\\s+instructions?\\b"),
            Pattern.compile("(?i)\\bsystem\\s*prompt\\b"),
            Pattern.compile("(?i)\\breveal\\s+(?:your|the)\\b"),
            Pattern.compile("(?i)\\bprint\\s+(?:your|the)\\s+(?:system\\s+)?(?:prompt|instructions?)\\b"),
            Pattern.compile("(?i)<\\s*/?\\s*(?:system|assistant|im_start|im_end)\\s*>"));

    private static final Pattern UUID = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    /**
     * @param analysis   parsed model output
     * @param evidence   the evidence package the model was given; cited ids must come
     *                   from here, which is what makes "evidence-backed" checkable
     * @return the analysis, guaranteed safe to store and display
     * @throws ValidationFailure with a machine-readable code for auditing
     */
    public IncidentAnalysis validate(IncidentAnalysis analysis, EvidenceIndex evidence) {
        if (analysis == null) {
            throw new ValidationFailure("EMPTY_RESPONSE", "The model returned no parseable content");
        }

        String summary = requireText(analysis.summary(), "summary", MAX_SUMMARY_CHARS);
        assertNoInjectionMarkers(summary, "summary");

        List<IncidentAnalysis.Hypothesis> hypotheses = analysis.hypotheses();
        if (hypotheses == null || hypotheses.isEmpty()) {
            throw new ValidationFailure("NO_HYPOTHESES", "The model returned no hypotheses");
        }
        if (hypotheses.size() > MAX_CAUSES) {
            throw new ValidationFailure("TOO_MANY_HYPOTHESES",
                    "Expected at most " + MAX_CAUSES + " hypotheses, got " + hypotheses.size());
        }

        List<IncidentAnalysis.Hypothesis> validated = new ArrayList<>(hypotheses.size());
        for (int i = 0; i < hypotheses.size(); i++) {
            validated.add(validateHypothesis(hypotheses.get(i), i, evidence));
        }

        List<String> missing = validateTextList(analysis.missingEvidence(), MAX_MISSING_EVIDENCE, "missingEvidence");
        List<String> caveats = validateTextList(analysis.caveats(), 6, "caveats");

        return new IncidentAnalysis(summary, List.copyOf(validated), missing, caveats);
    }

    private IncidentAnalysis.Hypothesis validateHypothesis(IncidentAnalysis.Hypothesis hypothesis, int index,
                                                          EvidenceIndex evidence) {
        String field = "hypotheses[" + index + "]";
        if (hypothesis == null) {
            throw new ValidationFailure("NULL_HYPOTHESIS", field + " is null");
        }

        String cause = requireText(hypothesis.cause(), field + ".cause", MAX_TEXT_CHARS);
        assertNoInjectionMarkers(cause, field + ".cause");

        double confidence = hypothesis.confidence();
        if (Double.isNaN(confidence) || confidence < 0.0d || confidence > 1.0d) {
            throw new ValidationFailure("CONFIDENCE_OUT_OF_RANGE",
                    field + ".confidence must be between 0 and 1, got " + confidence);
        }

        List<String> evidenceLines = validateTextList(hypothesis.evidence(), MAX_EVIDENCE_PER_HYPOTHESIS,
                field + ".evidence");
        if (evidenceLines.isEmpty()) {
            // A hypothesis with no evidence is a guess. Rejecting it forces the
            // model to ground its answer, which is the whole point of the module.
            throw new ValidationFailure("UNSUPPORTED_HYPOTHESIS",
                    field + " cites no supporting evidence");
        }

        List<String> checks = validateTextList(hypothesis.nextChecks(), MAX_CHECKS, field + ".nextChecks");
        if (checks.isEmpty()) {
            throw new ValidationFailure("NO_NEXT_CHECKS",
                    field + " suggests no next diagnostic step");
        }

        List<String> citedIds = validateCitedEventIds(hypothesis.evidenceEventIds(), evidence, field);
        assertNoInjectionMarkers(String.join(" ", checks), field + ".nextChecks");

        return new IncidentAnalysis.Hypothesis(cause, confidence, evidenceLines, checks, citedIds);
    }

    /**
     * A citation to an event that was never in the evidence package means either
     * hallucination or leakage from an earlier run. Either way the response cannot
     * be trusted.
     */
    private List<String> validateCitedEventIds(List<String> cited, EvidenceIndex evidence, String field) {
        if (cited == null || cited.isEmpty()) {
            // Citations are strongly encouraged but optional: a model may cite
            // evidence descriptively. Absence is not fabrication.
            return List.of();
        }
        if (cited.size() > MAX_EVIDENCE_PER_HYPOTHESIS) {
            throw new ValidationFailure("TOO_MANY_CITATIONS",
                    field + " cites more than " + MAX_EVIDENCE_PER_HYPOTHESIS + " events");
        }
        List<String> normalised = new ArrayList<>(cited.size());
        for (String raw : cited) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = UUID.matcher(raw.trim()).results()
                    .map(result -> result.group())
                    .findFirst()
                    .orElseThrow(() -> new ValidationFailure("INVALID_CITATION",
                            field + " cites a value that is not an event id: " + truncate(raw)));
            // Lower-cased before the lookup, because the index is lower-cased. Models
            // emit upper-case hex for UUIDs often enough that a raw comparison would
            // reject a correct citation.
            String key = id.toLowerCase(Locale.ROOT);
            if (!evidence.eventIds().contains(key)) {
                throw new ValidationFailure("UNKNOWN_CITATION",
                        field + " cites event " + id + ", which was not part of the evidence package");
            }
            normalised.add(key);
        }
        return List.copyOf(normalised);
    }

    private String requireText(String value, String field, int maxChars) {
        if (value == null || value.isBlank()) {
            throw new ValidationFailure("MISSING_FIELD", field + " must not be blank");
        }
        String trimmed = value.trim();
        if (trimmed.length() > maxChars) {
            throw new ValidationFailure("FIELD_TOO_LONG",
                    field + " exceeds " + maxChars + " characters");
        }
        return trimmed;
    }

    private List<String> validateTextList(List<String> values, int maxSize, String field) {
        if (values == null) {
            return List.of();
        }
        List<String> cleaned = values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .map(value -> value.length() > MAX_TEXT_CHARS ? truncate(value) : value)
                .distinct()
                .toList();
        if (cleaned.size() > maxSize) {
            throw new ValidationFailure("LIST_TOO_LONG",
                    field + " has " + cleaned.size() + " entries, maximum is " + maxSize);
        }
        return cleaned;
    }

    private void assertNoInjectionMarkers(String text, String field) {
        for (Pattern marker : INJECTION_MARKERS) {
            if (marker.matcher(text).find()) {
                throw new ValidationFailure("PROMPT_INJECTION_SUSPECTED",
                        field + " restates instruction-like text found in the evidence; the response was discarded");
            }
        }
    }

    private String truncate(String value) {
        return value.length() <= MAX_TEXT_CHARS ? value : value.substring(0, MAX_TEXT_CHARS - 1) + "…";
    }

    /** Evidence ids available to cite. Built once per analysis. */
    public record EvidenceIndex(Set<String> eventIds) {

        public static EvidenceIndex of(Set<String> ids) {
            return new EvidenceIndex(ids.stream()
                    .map(id -> id.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet()));
        }

        public static EvidenceIndex empty() {
            return new EvidenceIndex(Set.of());
        }
    }

    /** Validation failure with a code that is stored on the analysis row. */
    public static class ValidationFailure extends RuntimeException {

        private final String code;

        public ValidationFailure(String code, String message) {
            super(message);
            this.code = code;
        }

        public String getCode() {
            return code;
        }
    }
}
