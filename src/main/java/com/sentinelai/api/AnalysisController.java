package com.sentinelai.api;

import com.sentinelai.common.NotFoundException;
import com.sentinelai.common.PageResponse;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.investigation.AiAnalysisEntity;
import com.sentinelai.investigation.AiAnalysisRepository;
import com.sentinelai.investigation.AnalysisStatus;
import com.sentinelai.investigation.AnalysisViews;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The AI investigation feed, across all incidents.
 *
 * <p>This exists separately from {@code /incidents/{id}/analyses} because the two
 * questions a reviewer asks are different: "what did the model say about *this*
 * incident" is incident-scoped, while "what has the model been doing all day, and
 * how often does it fail" is fleet-scoped. The second question is what an operator
 * watches to decide whether to trust the feature at all.
 */
@RestController
@RequestMapping("/api/v1/analyses")
public class AnalysisController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "completedAt", "durationMs", "status");

    private final AiAnalysisRepository analyses;
    private final IncidentRepository incidents;

    public AnalysisController(AiAnalysisRepository analyses, IncidentRepository incidents) {
        this.analyses = analyses;
        this.incidents = incidents;
    }

    /**
     * Newest first by default.
     *
     * <p>Enrichment (incident reference, title, service, severity) is a single
     * batched lookup per page rather than a query per row. The obvious
     * implementation — dereference {@code analysis.getIncident()} — costs one query
     * per row and is exactly the shape that turns a 25-row page into 50 queries.
     */
    @GetMapping
    public PageResponse<AnalysisViews.Summary> list(
            @RequestParam(required = false) AnalysisStatus status,
            @RequestParam(required = false) UUID incidentId,
            @RequestParam(required = false) String modelName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "false") boolean ascending) {

        Pageable pageable = PageRequest.of(
                Math.max(0, page),
                size == null ? ApiSupport.DEFAULT_PAGE_SIZE : Math.clamp(size, 1, ApiSupport.MAX_PAGE_SIZE),
                ApiSupport.sort(sort, ascending, SORTABLE, Sort.by(Sort.Direction.DESC, "createdAt")));

        Page<AiAnalysisEntity> result = analyses.findAll(filter(status, incidentId, modelName), pageable);
        Map<UUID, IncidentEntity> context = incidentContext(result.getContent());

        List<AnalysisViews.Summary> rows = result.getContent().stream()
                .map(analysis -> {
                    IncidentEntity incident = context.get(analysis.getIncidentId());
                    return AnalysisViews.toSummary(analysis,
                            incident == null ? null : incident.getReference(),
                            incident == null ? null : incident.getTitle(),
                            incident == null ? null : incident.getService().getQualifiedName(),
                            incident == null ? null : incident.getSeverity());
                })
                .toList();

        return new PageResponse<>(rows, result.getNumber(), result.getSize(),
                result.getTotalElements(), result.getTotalPages(), result.hasNext());
    }

    /**
     * Each unset filter contributes nothing rather than a default, so "not filtered by
     * status" and "filtered to no status" can never be confused.
     */
    private static Specification<AiAnalysisEntity> filter(AnalysisStatus status, UUID incidentId,
                                                          String modelName) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (status != null) {
                predicates.add(builder.equal(root.get("status"), status));
            }
            if (incidentId != null) {
                predicates.add(builder.equal(root.get("incidentId"), incidentId));
            }
            if (modelName != null && !modelName.isBlank()) {
                predicates.add(builder.equal(root.get("modelName"), modelName.trim()));
            }
            return predicates.isEmpty() ? builder.conjunction()
                    : builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    @GetMapping("/{analysisId}")
    public AnalysisViews.Detail get(@PathVariable UUID analysisId) {
        AiAnalysisEntity analysis = analyses.findById(analysisId)
                .orElseThrow(() -> NotFoundException.of("Analysis", analysisId));
        return AnalysisViews.toDetail(analysis, referenceOf(analysis));
    }

    /**
     * Analyses are cited in postmortems by reference ("AI-204"), so they need to be
     * addressable that way exactly as incidents are.
     */
    @GetMapping("/by-reference/{reference}")
    public AnalysisViews.Detail getByReference(@PathVariable String reference) {
        String normalised = reference.trim().toUpperCase(Locale.ROOT);
        AiAnalysisEntity analysis = analyses.findByReference(normalised)
                .orElseThrow(() -> NotFoundException.of("Analysis", normalised));
        return AnalysisViews.toDetail(analysis, referenceOf(analysis));
    }

    /**
     * The verbatim provider response, for postmortems and for debugging a rejection.
     *
     * <p>ADMIN only. This text is unvalidated — it is exactly what the model emitted,
     * including any prompt-injected instructions that arrived inside a log payload
     * and were quoted back. Exposing it to a read-only role would mean shipping
     * untrusted instruction-shaped text into a UI that a human will read. The
     * validated, cited analysis in {@link #get} is the version everyone else needs.
     */
    @GetMapping("/{analysisId}/raw-response")
    @PreAuthorize("hasRole('ADMIN')")
    public RawResponse rawResponse(@PathVariable UUID analysisId) {
        AiAnalysisEntity analysis = analyses.findById(analysisId)
                .orElseThrow(() -> NotFoundException.of("Analysis", analysisId));
        return new RawResponse(analysis.getId(), analysis.getReference(), analysis.getModelName(),
                analysis.getStatus(), analysis.getPromptVersion(), analysis.getRawResponse(),
                analysis.getCompletedAt());
    }

    private String referenceOf(AiAnalysisEntity analysis) {
        return incidents.findById(analysis.getIncidentId())
                .map(IncidentEntity::getReference)
                .orElse(null);
    }

    private Map<UUID, IncidentEntity> incidentContext(List<AiAnalysisEntity> rows) {
        List<UUID> ids = rows.stream()
                .map(AiAnalysisEntity::getIncidentId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return incidents.findAllById(ids).stream()
                .collect(Collectors.toMap(IncidentEntity::getId, Function.identity()));
    }

    /**
     * Deliberately transparent about what this is: untrusted text.
     *
     * <p>The field is named {@code unvalidated} rather than {@code content} on purpose.
     * A neutral name invites a client to render it as though it carried the same weight
     * as the validated analysis; this one cannot be mistaken for anything other than
     * the raw, unchecked reply — including any instruction-shaped text that arrived
     * inside a log payload and was quoted back.
     */
    public record RawResponse(UUID analysisId, String reference, String modelName, AnalysisStatus status,
                              String promptVersion, String unvalidated, java.time.Instant completedAt) {
    }
}