package com.sentinelai.ingestion;

import com.sentinelai.common.PayloadRejectedException;
import com.sentinelai.config.IngestionProperties;
import com.sentinelai.detection.DetectionOrchestrator;
import com.sentinelai.detection.DetectionOutcome;
import com.sentinelai.detection.ErrorSignatureExtractor;
import com.sentinelai.events.EventEntity;
import com.sentinelai.events.EventRepository;
import com.sentinelai.events.ServiceEntity;
import com.sentinelai.events.ServiceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts telemetry and turns it into incident state.
 *
 * <p>Deliberately <em>not</em> a single {@code @Transactional} method. Idempotency
 * depends on the unique constraint being violated by a competing writer, and a
 * failed statement inside a transaction marks that transaction rollback-only — so
 * a duplicate would poison the very transaction that has to report it. Instead each
 * step runs in its own transaction:
 *
 * <ol>
 *   <li>store the event (own transaction; a unique violation means "already have it")</li>
 *   <li>run detection (own transaction; incident + evidence + audit commit atomically)</li>
 * </ol>
 *
 * <p>A retry of the same {@code (scope, sourceEventId)} therefore returns the
 * original event with {@code duplicate=true} and does no detection work at all —
 * the strongest form of idempotency, because a retry cannot inflate
 * {@code event_count} either.
 */
@Service
public class EventIngestionService {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    /** A producer clock further ahead than this is almost certainly broken. */
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final EventRepository events;
    private final ServiceRepository services;
    private final DetectionOrchestrator orchestrator;
    private final IngestionProperties properties;
    private final TransactionTemplate writeTransaction;

    public EventIngestionService(EventRepository events, ServiceRepository services,
                                 DetectionOrchestrator orchestrator, IngestionProperties properties,
                                 PlatformTransactionManager transactionManager) {
        this.events = events;
        this.services = services;
        this.orchestrator = orchestrator;
        this.properties = properties;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    public IngestionResult ingest(IngestEventRequest request, String scopeHeader) {
        String scope = resolveScope(request, scopeHeader);
        validatePayload(request);

        ServiceEntity service = resolveService(request);

        EventEntity event = buildEvent(request, scope, service);
        StoreOutcome stored = storeWithIdempotency(event);

        if (stored.duplicate()) {
            return IngestionResult.duplicate(stored.event());
        }

        DetectionOutcome outcome = writeTransaction.execute(status -> orchestrator.process(stored.event().getId()));
        return IngestionResult.accepted(stored.event(), outcome);
    }

    private String resolveScope(IngestEventRequest request, String scopeHeader) {
        if (scopeHeader != null && !scopeHeader.isBlank()) {
            return scopeHeader.trim();
        }
        if (request.sourceScope() != null && !request.sourceScope().isBlank()) {
            return request.sourceScope().trim();
        }
        return IngestEventRequest.DEFAULT_SCOPE;
    }

    private void validatePayload(IngestEventRequest request) {
        Map<String, Object> metadata = request.metadata();
        if (metadata != null && metadata.size() > properties.maxMetadataEntries()) {
            throw new PayloadRejectedException("TOO_MANY_METADATA_FIELDS",
                    "metadata may contain at most " + properties.maxMetadataEntries() + " entries, got "
                            + metadata.size());
        }
        if (request.message().length() > properties.maxMessageLength()) {
            throw new PayloadRejectedException("MESSAGE_TOO_LONG",
                    "message may contain at most " + properties.maxMessageLength() + " characters");
        }
        long approximateBytes = (long) request.message().length()
                + (request.sourceEventId() == null ? 0 : request.sourceEventId().length())
                + estimateMetadataBytes(metadata);
        if (approximateBytes > properties.maxPayloadBytes()) {
            throw new PayloadRejectedException("PAYLOAD_TOO_LARGE",
                    "Payload of ~" + approximateBytes + " bytes exceeds the limit of "
                            + properties.maxPayloadBytes() + " bytes");
        }
        if (request.occurredAt().isAfter(Instant.now().plus(MAX_CLOCK_SKEW))) {
            throw new PayloadRejectedException("CLOCK_SKEW",
                    "occurredAt " + request.occurredAt() + " is more than "
                            + MAX_CLOCK_SKEW.toMinutes() + " minutes in the future");
        }
    }

    private long estimateMetadataBytes(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return 0;
        }
        return metadata.entrySet().stream()
                .mapToLong(entry -> entry.getKey().length()
                        + String.valueOf(entry.getValue()).length())
                .sum();
    }

    private ServiceEntity resolveService(IngestEventRequest request) {
        return services.findByNameIgnoreCaseAndEnvironmentIgnoreCase(
                        request.service().trim(), request.environment().trim())
                .orElseThrow(() -> new PayloadRejectedException("UNKNOWN_SERVICE",
                        "No monitored service " + request.environment().trim() + "/"
                                + request.service().trim() + ". Register it first via POST /api/v1/services. "
                                + "Known services: " + knownServiceNames()));
    }

    private List<String> knownServiceNames() {
        return services.findAll().stream().map(ServiceEntity::getQualifiedName).sorted().toList();
    }

    private EventEntity buildEvent(IngestEventRequest request, String scope, ServiceEntity service) {
        Map<String, Object> metadata = PayloadSanitizer.sanitize(request.metadata());
        String message = PayloadSanitizer.sanitize(request.message().trim());
        String signature = ErrorSignatureExtractor.fromMetadata(message, metadata);
        return new EventEntity(
                UUID.randomUUID(),
                scope,
                request.sourceEventId().trim(),
                service,
                request.eventType(),
                request.severity(),
                message.length() > properties.maxMessageLength()
                        ? message.substring(0, properties.maxMessageLength())
                        : message,
                signature,
                request.occurredAt(),
                Instant.now(),
                metadata);
    }

    /**
     * Attempts the insert and treats a unique violation as "already ingested".
     *
     * <p>The pre-read is an optimisation that keeps the common retry path cheap.
     * The constraint is the actual guarantee, because between the read and the
     * insert a concurrent producer may have committed the same event — and only
     * the database can arbitrate that race correctly.
     */
    private StoreOutcome storeWithIdempotency(EventEntity candidate) {
        EventEntity existing = events
                .findBySourceScopeAndSourceEventId(candidate.getSourceScope(), candidate.getSourceEventId())
                .orElse(null);
        if (existing != null) {
            return new StoreOutcome(existing, true);
        }

        try {
            EventEntity saved = writeTransaction.execute(status -> {
                EventEntity persisted = events.saveAndFlush(candidate);
                status.flush();
                return persisted;
            });
            if (saved == null) {
                throw new IllegalStateException("Event transaction returned no entity");
            }
            return new StoreOutcome(saved, false);
        } catch (DataIntegrityViolationException ex) {
            EventEntity winner = events
                    .findBySourceScopeAndSourceEventId(candidate.getSourceScope(), candidate.getSourceEventId())
                    .orElseThrow(() -> ex);
            log.debug("Duplicate event {} from scope {} discarded", candidate.getSourceEventId(),
                    candidate.getSourceScope());
            return new StoreOutcome(winner, true);
        }
    }

    private record StoreOutcome(EventEntity event, boolean duplicate) {
    }

    /** Response body for {@code POST /api/v1/events}. */
    public record IngestionResult(
            UUID eventId,
            String sourceEventId,
            boolean duplicate,
            Instant receivedAt,
            IncidentPointer incident,
            List<String> matchedRules
    ) {

        static IngestionResult accepted(EventEntity event, DetectionOutcome outcome) {
            return new IngestionResult(
                    event.getId(),
                    event.getSourceEventId(),
                    false,
                    event.getReceivedAt(),
                    outcome.triggered() && outcome.incidentId() != null
                            ? new IncidentPointer(outcome.incidentId(), outcome.incidentReference(),
                                    outcome.action())
                            : outcome.triggered() ? new IncidentPointer(null, null, outcome.action()) : null,
                    outcome.matchedRuleCodes());
        }

        static IngestionResult duplicate(EventEntity event) {
            return new IngestionResult(event.getId(), event.getSourceEventId(), true, event.getReceivedAt(),
                    null, List.of());
        }
    }

    public record IncidentPointer(UUID incidentId, String reference, String action) {
    }
}
