package com.sentinelai.investigation;

import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.incidents.IncidentStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Retrieves previously resolved incidents to give the model precedent.
 *
 * <p>Similarity is Jaccard overlap over the tokens of the two error signatures,
 * plus a bonus for a shared correlation group. That is a deliberately simple,
 * explainable ranking: a reviewer can see exactly why an earlier incident was
 * offered. An embedding model would likely score better on recall, but it would
 * also be far harder to justify and would add a dependency before the core
 * system needed one.
 *
 * <p>Only incidents a human <em>resolved with a verified cause</em> are eligible.
 * An AI hypothesis must never be able to reinforce itself as precedent.
 */
@Service
public class IncidentKnowledgeService {

    private static final Duration LOOKBACK = Duration.ofDays(30);
    private static final int CANDIDATES = 40;
    private static final int MAX_RESULTS = 3;
    private static final double CORRELATION_GROUP_BONUS = 0.15d;
    private static final double MIN_OVERLAP = 0.20d;

    private final IncidentRepository incidents;

    public IncidentKnowledgeService(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    @Transactional(readOnly = true)
    public List<IncidentContext.PriorIncident> findSimilarResolved(IncidentEntity current) {
        if (current.getErrorSignature() == null) {
            return List.of();
        }
        Set<String> currentTokens = tokenise(current.getErrorSignature());
        if (currentTokens.isEmpty()) {
            return List.of();
        }

        Instant cutoff = current.getFirstSeenAt() == null
                ? Instant.now().minus(LOOKBACK)
                : current.getFirstSeenAt().minus(LOOKBACK);

        List<IncidentEntity> candidates = incidents.findByServiceIdAndStatusAndResolvedAtAfterOrderByResolvedAtDesc(
                current.getService().getId(), IncidentStatus.RESOLVED, cutoff, Limit.of(CANDIDATES));

        record Scored(IncidentEntity incident, double overlap) {
        }

        return candidates.stream()
                .filter(candidate -> candidate.getResolvedRootCause() != null
                        && !candidate.getResolvedRootCause().isBlank())
                .map(candidate -> new Scored(candidate, score(currentTokens, current, candidate)))
                .filter(scored -> scored.overlap() >= MIN_OVERLAP)
                .sorted((a, b) -> Double.compare(b.overlap(), a.overlap()))
                .limit(MAX_RESULTS)
                .map(scored -> new IncidentContext.PriorIncident(
                        scored.incident().getReference(),
                        scored.incident().getService().getQualifiedName(),
                        scored.incident().getResolvedAt(),
                        scored.incident().getResolvedRootCause(),
                        scored.incident().getPreventiveActions(),
                        scored.overlap()))
                .toList();
    }

    private double score(Set<String> currentTokens, IncidentEntity current, IncidentEntity candidate) {
        Set<String> candidateTokens = tokenise(candidate.getErrorSignature());
        if (candidateTokens.isEmpty()) {
            return 0d;
        }
        Set<String> intersection = new HashSet<>(currentTokens);
        intersection.retainAll(candidateTokens);
        Set<String> union = new HashSet<>(currentTokens);
        union.addAll(candidateTokens);
        double jaccard = union.isEmpty() ? 0d : (double) intersection.size() / union.size();

        boolean sameGroup = current.getCorrelationGroup() != null
                && current.getCorrelationGroup().equals(candidate.getCorrelationGroup());
        return Math.min(1d, jaccard + (sameGroup ? CORRELATION_GROUP_BONUS : 0d));
    }

    /**
     * Signature signatures look like {@code "connection pool exhausted for dependency:pg"},
     * so tokens come from the alphabetic runs only, with a short stop list to
     * prevent "for" and "the" from creating spurious overlap.
     */
    static Set<String> tokenise(String signature) {
        if (signature == null || signature.isBlank()) {
            return Set.of();
        }
        Set<String> stopWords = Set.of("for", "the", "a", "an", "of", "on", "in", "to", "with", "and", "or");
        return Arrays.stream(signature.toLowerCase(Locale.ROOT).split("[^a-z0-9]+"))
                .filter(token -> token.length() > 2)
                .filter(token -> !stopWords.contains(token))
                .map(String::strip)
                .collect(java.util.stream.Collectors.toCollection(HashSet::new));
    }
}
