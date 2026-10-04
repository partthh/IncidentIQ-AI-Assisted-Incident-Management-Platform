package com.sentinelai.investigation;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelai.config.InvestigationProperties;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.investigation.llm.AnalysisJsonParser;
import com.sentinelai.investigation.llm.LlmClient;
import com.sentinelai.investigation.llm.LlmException;
import com.sentinelai.investigation.llm.LlmRequest;
import com.sentinelai.investigation.llm.LlmResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Turns one incident plus one evidence package into one trustworthy analysis.
 *
 * <p>Everything in this class exists because the model's output is untrusted input
 * with a convenient syntax. The pipeline is deliberately linear and each stage can
 * only hand the next one a narrowed problem:
 *
 * <ol>
 *   <li><b>Assemble</b> a bounded evidence package ({@link IncidentContextBuilder}).
 *       Nothing is sent to a provider that has not been capped first.</li>
 *   <li><b>Call</b> the provider with a finite timeout, retrying only failures a
 *       retry could plausibly fix.</li>
 *   <li><b>Parse</b> leniently, because models wrap JSON in prose and vary their
 *       field shapes.</li>
 *   <li><b>Validate</b> strictly, because a well-formed object can still be
 *       uncited, incoherent, or hijacked by a log line.</li>
 * </ol>
 *
 * <p>The split between steps 3 and 4 is the important one. "Could I read it" and
 * "should I believe it" are different questions with different failure responses:
 * the first retries or reports a malformed response, the second refuses outright.
 * Merging them is how systems end up displaying confident nonsense.
 *
 * <p>This class deliberately holds no transaction. The model call is slow and
 * remote; holding a database transaction across it would pin a connection from the
 * pool for the duration of someone else's outage. The caller owns the transaction
 * boundaries.
 */
@Service
public class IncidentAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(IncidentAnalysisService.class);

    private final IncidentContextBuilder contextBuilder;
    private final AnalysisJsonParser parser;
    private final AnalysisResponseValidator validator;
    private final LlmClient llmClient;
    private final InvestigationProperties properties;

    public IncidentAnalysisService(IncidentContextBuilder contextBuilder,
                                   AnalysisJsonParser parser,
                                   AnalysisResponseValidator validator,
                                   LlmClient llmClient,
                                   InvestigationProperties properties) {
        this.contextBuilder = contextBuilder;
        this.parser = parser;
        this.validator = validator;
        this.llmClient = llmClient;
        this.properties = properties;
    }

    /**
     * Runs one investigation attempt end to end.
     *
     * @throws LlmException if the provider failed and the caller should retry
     */
    public Outcome analyse(IncidentEntity incident, String requestTag) {
        IncidentContext context = contextBuilder.build(incident);
        String renderedEvidence = contextBuilder.renderEvidence(context);

        LlmRequest request = new LlmRequest(
                InvestigationPrompt.systemPrompt(),
                InvestigationPrompt.userPrompt(context, renderedEvidence),
                properties.maxRawTokens(),
                requestTag,
                context);

        LlmResponse response = callWithRetries(request);

        IncidentAnalysis parsed;
        try {
            parsed = parser.parse(response.rawContent());
        } catch (RuntimeException ex) {
            // Not retried. The provider answered; re-sending the identical prompt to
            // the same model reliably produces the same unreadable answer, and
            // burning the attempt budget would delay the FAILED status. The unreadable
            // text travels with the exception so the row records what actually arrived.
            throw new LlmException(LlmException.Kind.MALFORMED_RESPONSE,
                    "Could not parse the model response as the agreed JSON contract: " + ex.getMessage(),
                    0, ex, response.rawContent());
        }

        IncidentAnalysis validated;
        try {
            validated = validator.validate(parsed, context.evidenceIndex());
        } catch (AnalysisResponseValidator.ValidationFailure failure) {
            // Also terminal, and for a stronger reason: the model produced something
            // we can read and have decided it is not trustworthy. Retrying the same
            // evidence risks a different wrong answer, and accepting it would put
            // unverified text in front of an engineer.
            throw new RejectedAnalysisException(failure.getCode(), failure.getMessage(),
                    response.rawContent());
        }

        return new Outcome(validated, response, context, renderedEvidence);
    }

    /**
     * Retries only what a retry could fix, with capped exponential backoff.
     *
     * <p>The attempt count is bounded by configuration rather than by wall-clock
     * because an unbounded retry loop against a provider that is down converts one
     * incident's analysis into a self-inflicted load problem.
     */
    private LlmResponse callWithRetries(LlmRequest request) {
        int maxAttempts = Math.max(1, properties.maxAttempts());
        LlmException lastFailure = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return llmClient.complete(request);
            } catch (LlmException failure) {
                lastFailure = failure;
                boolean attemptsLeft = attempt < maxAttempts;
                if (!failure.isRetryable() || !attemptsLeft) {
                    log.warn("Model call {} failed on attempt {}/{}: kind={} {}",
                            request.requestTag(), attempt, maxAttempts, failure.getKind(), failure.getMessage());
                    throw failure;
                }
                Duration backoff = backoffFor(attempt);
                log.info("Model call {} attempt {}/{} failed ({}); retrying in {}",
                        request.requestTag(), attempt, maxAttempts, failure.getKind(), backoff);
                sleep(backoff);
            }
        }
        // Unreachable: the loop either returns or throws. Present so the compiler can
        // prove the method never falls through with an uninitialised result.
        throw lastFailure == null
                ? new LlmException(LlmException.Kind.SERVER_ERROR, "Model call produced no response")
                : lastFailure;
    }

    /**
     * Base backoff, doubled per attempt, capped. The cap matters: without it, a
     * five-attempt configuration would sleep for over half a minute.
     */
    private Duration backoffFor(int attempt) {
        Duration base = properties.retryBackoff();
        long millis = Math.multiplyExact(Math.max(1L, base.toMillis()), 1L << (attempt - 1));
        return Duration.ofMillis(Math.min(millis, Duration.ofSeconds(30).toMillis()));
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new LlmException(LlmException.Kind.TIMEOUT, "Interrupted while backing off before a retry");
        }
    }

    /** Result of a successful, validated investigation. */
    public record Outcome(
            IncidentAnalysis analysis,
            LlmResponse response,
            IncidentContext context,
            String renderedEvidence
    ) {

        /** The analysis as it will be stored and returned to clients. */
        public Map<String, Object> toAnalysisJson(ObjectMapper mapper) {
            return mapper.convertValue(analysis, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        }

        /**
         * Exactly what the model was given, so a hypothesis can be re-checked later
         * against the same evidence rather than against whatever the logs look like
         * now.
         */
        public Map<String, Object> toEvidenceSnapshot(ObjectMapper mapper) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("context", mapper.convertValue(context, new TypeReference<LinkedHashMap<String, Object>>() {
            }));
            snapshot.put("renderedEvidence", renderedEvidence);
            return snapshot;
        }

        public Map<String, Object> toUsage(LlmResponse response) {
            Map<String, Object> usage = new LinkedHashMap<>();
            usage.put("model", response.model());
            if (response.promptTokens() != null) {
                usage.put("promptTokens", response.promptTokens());
            }
            if (response.completionTokens() != null) {
                usage.put("completionTokens", response.completionTokens());
            }
            return usage;
        }
    }

    /**
     * The provider answered, but the answer failed validation. Carries the raw
     * response so the row can record what actually arrived.
     */
    public static class RejectedAnalysisException extends RuntimeException {

        private final String code;
        private final String rawResponse;

        public RejectedAnalysisException(String code, String message, String rawResponse) {
            super(message);
            this.code = code;
            this.rawResponse = rawResponse;
        }

        public String getCode() {
            return code;
        }

        public String getRawResponse() {
            return rawResponse;
        }
    }
}