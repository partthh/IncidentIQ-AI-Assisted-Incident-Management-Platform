package com.sentinelai.api;

import com.sentinelai.common.EventType;
import com.sentinelai.common.NotFoundException;
import com.sentinelai.common.PageResponse;
import com.sentinelai.common.Severity;
import com.sentinelai.events.EventEntity;
import com.sentinelai.events.EventRepository;
import com.sentinelai.ingestion.EventIngestionService;
import com.sentinelai.ingestion.EventIngestionService.IngestionResult;
import com.sentinelai.ingestion.IngestEventRequest;
import jakarta.persistence.criteria.Predicate;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
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
 * Telemetry ingestion and search.
 *
 * <p>{@code POST /events} is the only endpoint a producer needs, and it is
 * deliberately hard to misuse: the idempotency key is mandatory, the payload is size
 * checked before anything is stored, and the response says exactly what detection did
 * with the event so a producer never has to poll to find out.
 *
 * <p>Ingestion is restricted to ADMIN and ENGINEER. A VIEWER exists to watch the
 * dashboard; letting it write telemetry would let anyone manufacture incidents.
 */
@RestController
@RequestMapping("/api/v1/events")
public class EventController {

    private static final Sort DEFAULT_SORT = Sort.by(Sort.Direction.DESC, "occurredAt");

    private final EventIngestionService ingestion;
    private final EventRepository events;

    public EventController(EventIngestionService ingestion, EventRepository events) {
        this.ingestion = ingestion;
        this.events = events;
    }

    /**
     * Accepts one event.
     *
     * <p>200 for a stored event and 202 for one that triggered detection. A duplicate
     * returns 200 with {@code duplicate: true} — a success, not an error, because the
     * producer achieved exactly what it asked for and must not retry.
     */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','ENGINEER')")
    public ResponseEntity<IngestionResult> ingest(@Valid @RequestBody IngestEventRequest request,
                                                  @RequestHeader(value = IngestEventRequest.SOURCE_HEADER,
                                                          required = false) String sourceScope) {
        IngestionResult result = ingestion.ingest(request, sourceScope);
        HttpStatus status = result.duplicate() || result.incident() == null
                ? HttpStatus.OK
                : HttpStatus.ACCEPTED;
        return ResponseEntity.status(status).body(result);
    }

    /**
     * Searches stored events.
     *
     * <p>Reads {@code occurredAt}, never {@code receivedAt}: an operator asking "what
     * did the service report at 14:02" means event time. Search by ingestion time is
     * available as {@code receivedSince} for the opposite question — "what arrived
     * late".
     */
    @GetMapping
    public PageResponse<EventView> list(
            @RequestParam(required = false) UUID serviceId,
            @RequestParam(required = false) String service,
            @RequestParam(required = false) EventType type,
            @RequestParam(required = false) Severity severity,
            @RequestParam(required = false) String signature,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) Instant receivedSince,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(required = false) Integer size) {

        Pageable pageable = org.springframework.data.domain.PageRequest.of(
                Math.max(0, page),
                size == null ? ApiSupport.DEFAULT_PAGE_SIZE : Math.clamp(size, 1, ApiSupport.MAX_PAGE_SIZE),
                DEFAULT_SORT);

        return PageResponse.from(events.findAll(specification(serviceId, service, type, severity,
                signature, search, from, to, receivedSince), pageable), EventView::from);
    }

    @GetMapping("/{eventId}")
    public EventView get(@PathVariable UUID eventId) {
        // findByIdWithService, not findById: the view needs the service name, and the
        // repository call has already closed its transaction by the time we read it.
        return events.findByIdWithService(eventId)
                .map(EventView::from)
                .orElseThrow(() -> NotFoundException.of("Event", eventId));
    }

    Specification<EventEntity> specification(UUID serviceId, String service, EventType type,
                                             Severity severity, String signature, String search,
                                             Instant from, Instant to, Instant receivedSince) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();
            if (serviceId != null) {
                predicates.add(builder.equal(root.get("service").get("id"), serviceId));
            }
            if (service != null && !service.isBlank()) {
                predicates.add(builder.equal(builder.lower(root.get("service").get("name")),
                        service.trim().toLowerCase(java.util.Locale.ROOT)));
            }
            if (type != null) {
                predicates.add(builder.equal(root.get("eventType"), type));
            }
            if (severity != null) {
                predicates.add(builder.equal(root.get("severity"), severity));
            }
            if (signature != null && !signature.isBlank()) {
                predicates.add(builder.equal(root.get("errorSignature"), signature.trim()));
            }
            if (search != null && !search.isBlank()) {
                predicates.add(builder.like(builder.lower(root.get("message")),
                        "%" + search.trim().toLowerCase(java.util.Locale.ROOT) + "%"));
            }
            if (from != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("occurredAt"), from));
            }
            if (to != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("occurredAt"), to));
            }
            if (receivedSince != null) {
                predicates.add(builder.greaterThanOrEqualTo(root.get("receivedAt"), receivedSince));
            }
            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * One stored event.
     *
     * <p>{@code receivedAt} is included alongside {@code occurredAt} on purpose: the
     * gap between them is the late-arrival signal, and hiding one of the two would
     * make it impossible to see.
     */
    public record EventView(
            UUID id,
            String sourceScope,
            String sourceEventId,
            UUID serviceId,
            String service,
            String environment,
            EventType eventType,
            Severity severity,
            String message,
            String errorSignature,
            Instant occurredAt,
            Instant receivedAt,
            Map<String, Object> metadata
    ) {
        static EventView from(EventEntity event) {
            return new EventView(
                    event.getId(),
                    event.getSourceScope(),
                    event.getSourceEventId(),
                    event.getService().getId(),
                    event.getService().getQualifiedName(),
                    event.getService().getEnvironment(),
                    event.getEventType(),
                    event.getSeverity(),
                    event.getMessage(),
                    event.getErrorSignature(),
                    event.getOccurredAt(),
                    event.getReceivedAt(),
                    event.getMetadata());
        }
    }
}