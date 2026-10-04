package com.sentinelai.timeline;

import com.sentinelai.incidents.IncidentEntity;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.security.AppUserRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the append-only audit trail.
 *
 * <p>{@link Propagation#MANDATORY} is the important design decision here: this
 * bean refuses to write anything unless it is already inside a transaction. An
 * audit entry can therefore never be committed without the state change it
 * describes, and never survive a rollback that undid that state change. Relying
 * on callers to remember {@code @Transactional} would be a convention; this is a
 * constraint the container enforces.
 */
@Service
public class TimelineService {

    private final TimelineRepository timeline;
    private final IncidentRepository incidents;
    private final AppUserRepository users;

    public TimelineService(TimelineRepository timeline, IncidentRepository incidents, AppUserRepository users) {
        this.timeline = timeline;
        this.incidents = incidents;
        this.users = users;
    }

    /**
     * Appends an entry, allocating the next per-incident sequence under the
     * incident's row lock.
     *
     * @return the new sequence number, which doubles as the client catch-up cursor
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long append(IncidentEntity incident, UUID actorId, String eventType, String summary,
                       Map<String, Object> payload) {
        // Re-read under a write lock: the caller may hold an instance loaded before
        // another transaction appended an entry, and sequence numbers must be dense
        // and strictly increasing or clients cannot resume from a cursor.
        IncidentEntity locked = incidents.findByIdForUpdate(incident.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "Incident " + incident.getReference() + " vanished mid-transaction"));

        locked.bumpTimelineSequence();
        long sequence = locked.getTimelineSeq();

        timeline.save(new TimelineEntryEntity(
                UUID.randomUUID(),
                locked.getId(),
                actorId,
                eventType,
                truncate(summary, 500),
                payload == null ? Map.of() : new LinkedHashMap<>(payload),
                sequence,
                Instant.now()));
        return sequence;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntryEntity> find(UUID incidentId, Long afterSequence, int limit) {
        int safeLimit = Math.clamp(limit, 1, 500);
        if (afterSequence != null) {
            return timeline.findByIncidentIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
                    incidentId, afterSequence, Limit.of(safeLimit));
        }
        return timeline.findByIncidentIdOrderBySequenceNoAsc(incidentId, Limit.of(safeLimit));
    }

    /**
     * The client-facing timeline, with actors resolved.
     *
     * <p>Names are loaded in one query for the whole page. A forty-entry history
     * produced by one engineer resolving an incident would otherwise be forty round
     * trips to render a single name.
     *
     * @param afterSequence when present, only entries strictly after this cursor,
     *                      which is how a reconnected client catches up
     */
    @Transactional(readOnly = true)
    public List<TimelineViews.Entry> findEntries(UUID incidentId, Long afterSequence, int limit) {
        List<TimelineEntryEntity> entries = find(incidentId, afterSequence, limit);
        Map<UUID, TimelineViews.Actor> actors = resolveActors(entries);
        return entries.stream()
                .map(entry -> new TimelineViews.Entry(
                        entry.getId(),
                        entry.getSequenceNo() == null ? 0L : entry.getSequenceNo(),
                        entry.getEventType(),
                        entry.getSummary(),
                        actors.get(entry.getActorId()),
                        entry.getPayload(),
                        entry.getCreatedAt()))
                .toList();
    }

    private Map<UUID, TimelineViews.Actor> resolveActors(List<TimelineEntryEntity> entries) {
        List<UUID> ids = entries.stream()
                .map(TimelineEntryEntity::getActorId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, TimelineViews.Actor> byId = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            users.findAllById(ids).forEach(user ->
                    byId.put(user.getId(), new TimelineViews.Actor(user.getId(), user.getName(),
                            user.getEmail(), user.getRole().name())));
        }
        return byId;
    }

    @Transactional(readOnly = true)
    public List<TimelineEntryEntity> findNotes(UUID incidentId, int limit) {
        return timeline.findByIncidentIdAndEventTypeOrderBySequenceNoAsc(
                incidentId, TimelineEventTypes.NOTE, Limit.of(Math.clamp(limit, 1, 200)));
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
