package com.sentinelai.api;

import com.sentinelai.common.NotFoundException;
import com.sentinelai.common.PageResponse;
import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentQueryService;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.incidents.IncidentService;
import com.sentinelai.incidents.IncidentStatus;
import com.sentinelai.incidents.IncidentViews;
import com.sentinelai.investigation.AiAnalysisRepository;
import com.sentinelai.investigation.AnalysisStatus;
import com.sentinelai.investigation.AnalysisViews;
import com.sentinelai.investigation.InvestigationQueueService;
import jakarta.validation.Valid;
import com.sentinelai.security.AppUserRepository;
import com.sentinelai.security.CurrentUser;
import com.sentinelai.security.SentinelPrincipal;
import com.sentinelai.timeline.TimelineService;
import com.sentinelai.timeline.TimelineViews;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The engineer-facing incident API.
 *
 * <p>Reads are open to every authenticated role; writes require ENGINEER or ADMIN.
 * The coarse rule is declared here with {@link PreAuthorize} rather than being left to
 * the URL-pattern configuration alone, so that adding an endpoint under this path
 * cannot accidentally ship it unprotected.
 *
 * <p>Concurrency is opt-in per request through {@code expectedVersion} (body) or
 * {@code If-Match} (header). Both are honoured because two different clients are
 * calling this API: a form posts a body, an HTTP client uses the header.
 */
@RestController
@RequestMapping("/api/v1/incidents")
public class IncidentController {

    /** Only indexed, meaningful-to-sort columns are accepted from the client. */
    private static final Set<String> SORTABLE = Set.of("lastSeenAt", "firstSeenAt", "severity",
            "status", "eventCount", "reference");
    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "lastSeenAt");

    private final IncidentService incidentService;
    private final IncidentQueryService queryService;
    private final IncidentRepository incidents;
    private final TimelineService timeline;
    private final InvestigationQueueService investigations;
    private final AiAnalysisRepository analyses;
    private final AppUserRepository users;

    public IncidentController(IncidentService incidentService, IncidentQueryService queryService,
                              IncidentRepository incidents, TimelineService timeline,
                              InvestigationQueueService investigations, AiAnalysisRepository analyses,
                              AppUserRepository users) {
        this.incidentService = incidentService;
        this.queryService = queryService;
        this.incidents = incidents;
        this.timeline = timeline;
        this.investigations = investigations;
        this.analyses = analyses;
        this.users = users;
    }

    @GetMapping
    public PageResponse<IncidentViews.Summary> list(
            @RequestParam(required = false) IncidentStatus status,
            @RequestParam(required = false) List<IncidentStatus> statuses,
            @RequestParam(required = false) com.sentinelai.common.Severity severity,
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) UUID assignedTo,
            @RequestParam(required = false) Boolean unassigned,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Boolean activeOnly,
            @RequestParam(required = false) java.time.Instant since,
            @RequestParam(required = false) java.time.Instant until,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "true") boolean ascending) {

        Pageable pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page),
                size == null ? ApiSupport.DEFAULT_PAGE_SIZE : Math.clamp(size, 1, ApiSupport.MAX_PAGE_SIZE),
                ApiSupport.sort(sort, ascending, SORTABLE, DEFAULT_SORT));

        IncidentQueryService.Filter filter = new IncidentQueryService.Filter(
                status, severity, statuses, serviceId, service, assignedTo, unassigned,
                search, since, until, Boolean.TRUE.equals(activeOnly));

        return queryService.search(filter, pageable);
    }

    @GetMapping("/{incidentId}")
    public IncidentViews.Detail get(@PathVariable UUID incidentId) {
        return incidentService.getDetail(incidentId);
    }

    /** Engineers talk in references ("INC-1042"), so they can be looked up directly. */
    @GetMapping("/by-reference/{reference}")
    public IncidentViews.Detail getByReference(@PathVariable String reference) {
        UUID id = incidents.findByReference(reference.trim().toUpperCase(java.util.Locale.ROOT))
                .map(incident -> incident.getId())
                .orElseThrow(() -> NotFoundException.of("Incident", reference));
        return incidentService.getDetail(id);
    }

    /**
     * The audit trail.
     *
     * @param afterSequence cursor from the last entry the client saw; the reconnect
     *                      path asks for exactly what it missed
     */
    @GetMapping("/{incidentId}/timeline")
    public TimelineResponse timeline(@PathVariable UUID incidentId,
                                     @RequestParam(required = false) Long afterSequence,
                                     @RequestParam(defaultValue = "200") int limit) {
        IncidentEntity incident = requireIncident(incidentId);
        int safeLimit = Math.clamp(limit, 1, 500);
        List<TimelineViews.Entry> entries = timeline.findEntries(incidentId, afterSequence, safeLimit);
        long cursor = incident.getTimelineSeq() == null ? 0L : incident.getTimelineSeq();
        boolean hasMore = entries.size() == safeLimit
                && entries.get(entries.size() - 1).sequence() < cursor;
        return new TimelineResponse(incidentId, cursor, hasMore, entries);
    }

    @PostMapping("/{incidentId}/acknowledge")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public ResponseEntity<IncidentViews.Detail> acknowledge(
            @PathVariable UUID incidentId,
            @RequestBody(required = false) IncidentViews.AcknowledgeRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Long expected = ApiSupport.expectedVersion(body == null ? null : body.expectedVersion(), ifMatch);
        return ok(incidentService.acknowledge(incidentId, CurrentUser.require(), expected));
    }

    @PostMapping("/{incidentId}/investigate")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public ResponseEntity<IncidentViews.Detail> investigate(
            @PathVariable UUID incidentId,
            @RequestBody(required = false) IncidentViews.AcknowledgeRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Long expected = ApiSupport.expectedVersion(body == null ? null : body.expectedVersion(), ifMatch);
        return ok(incidentService.startInvestigating(incidentId, CurrentUser.require(), expected));
    }

    @PostMapping("/{incidentId}/assignment")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public ResponseEntity<IncidentViews.Detail> assign(
            @PathVariable UUID incidentId,
            @RequestBody IncidentViews.AssignRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Long expected = ApiSupport.expectedVersion(body.expectedVersion(), ifMatch);
        return ok(incidentService.assign(incidentId, body.assignee(), expected, CurrentUser.require()));
    }

    @PostMapping("/{incidentId}/notes")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public ResponseEntity<IncidentViews.Detail> addNote(
            @PathVariable UUID incidentId,
            @RequestBody IncidentViews.NoteRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Long expected = ApiSupport.expectedVersion(body.expectedVersion(), ifMatch);
        return ok(incidentService.addNote(incidentId, body.body(), expected, CurrentUser.require()));
    }

    @PostMapping("/{incidentId}/resolve")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public ResponseEntity<IncidentViews.Detail> resolve(
            @PathVariable UUID incidentId,
            @RequestBody @Valid IncidentViews.ResolveRequest body,
            @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Long expected = ApiSupport.expectedVersion(body.expectedVersion(), ifMatch);
        return ok(incidentService.resolve(incidentId, body.rootCause(), body.preventiveActions(),
                expected, CurrentUser.require()));
    }

    /**
     * Requests an AI investigation.
     *
     * <p>Returns 202 rather than 200: the model call happens on the queue worker, and
     * the response promises work that has been durably recorded, not an answer.
     */
    @PostMapping("/{incidentId}/investigations")
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public ResponseEntity<AnalysisViews.QueueResponse> investigateWithAi(
            @PathVariable UUID incidentId,
            @RequestBody(required = false) AnalysisViews.InvestigateRequest body) {
        SentinelPrincipal actor = CurrentUser.require();
        AnalysisViews.QueueResponse queued =
                investigations.requestInvestigation(incidentId, actor, body == null ? null : body.note());
        return ResponseEntity.accepted().body(queued);
    }

    @GetMapping("/{incidentId}/analyses")
    public PageResponse<AnalysisViews.Summary> analyses(
            @PathVariable UUID incidentId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {
        IncidentEntity incident = requireIncident(incidentId);
        Pageable pageable = ApiSupport.pageable(page, size);
        return PageResponse.from(analyses.findByIncidentIdOrderByCreatedAtDesc(incidentId, pageable),
                analysis -> AnalysisViews.toSummary(analysis, incident.getReference(), incident.getTitle(),
                        incident.getService().getQualifiedName(), incident.getSeverity()));
    }

    /** The newest analysis, which is what the incident page renders. */
    @GetMapping("/{incidentId}/analyses/latest")
    public AnalysisViews.Detail latestAnalysis(@PathVariable UUID incidentId) {
        IncidentEntity incident = requireIncident(incidentId);
        return analyses.findFirstByIncidentIdOrderByCreatedAtDesc(incidentId)
                .map(analysis -> AnalysisViews.toDetail(analysis, incident.getReference()))
                .orElseThrow(() -> NotFoundException.of("Analysis for incident "
                        + incident.getReference(), "no investigation has completed yet"));
    }

    private IncidentEntity requireIncident(UUID incidentId) {
        return incidents.findById(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));
    }

    /**
     * People who can own incidents.
     *
     * <p>Exposed to every authenticated role rather than admins only: any engineer may
     * need to hand an incident to a colleague, and the list contains nothing an
     * engineer does not already see on the incident they are working.
     */
    @GetMapping("/assignable-users")
    public List<AssigneeOption> assignableUsers() {
        return users.findAssignableOrderByNameAsc().stream()
                .map(user -> new AssigneeOption(user.getId(), user.getName(), user.getEmail(), user.getRole()))
                .toList();
    }

    private ResponseEntity<IncidentViews.Detail> ok(IncidentViews.Detail detail) {
        return ResponseEntity.ok(detail);
    }

    /** Wraps the timeline so a reconnecting client learns the current cursor in one call. */
    public record TimelineResponse(UUID incidentId, long currentSequence, boolean hasMore,
                                   List<TimelineViews.Entry> entries) {
    }

    public record AssigneeOption(UUID id, String name, String email, com.sentinelai.common.Role role) {
    }
}