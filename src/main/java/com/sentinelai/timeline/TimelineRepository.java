package com.sentinelai.timeline;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimelineRepository extends JpaRepository<TimelineEntryEntity, UUID> {

    /**
     * Entries strictly after {@code afterSequence} — the reconnect catch-up
     * query. Ordered ascending because a client applies them in order.
     */
    List<TimelineEntryEntity> findByIncidentIdAndSequenceNoGreaterThanOrderBySequenceNoAsc(
            UUID incidentId, Long afterSequence, Limit limit);

    List<TimelineEntryEntity> findByIncidentIdOrderBySequenceNoAsc(UUID incidentId, Limit limit);

    List<TimelineEntryEntity> findByIncidentIdAndEventTypeOrderBySequenceNoAsc(
            UUID incidentId, String eventType, Limit limit);
}
