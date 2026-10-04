package com.sentinelai.incidents;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentEventRepository extends JpaRepository<IncidentEventEntity, IncidentEventEntity.Key> {

    long countByIdIncidentId(UUID incidentId);

    /**
     * Every event attached to an incident, oldest first, with the event initialised.
     * Evidence assembly runs in its own read-only transaction, so the fetch join is
     * what keeps the context builder from hitting a detached lazy proxy.
     */
    @Query("""
            select link from IncidentEventEntity link
            join fetch link.event e
            join fetch e.service
            where link.id.incidentId = :incidentId
            order by e.occurredAt asc
            """)
    List<IncidentEventEntity> findByIncidentIdWithEvent(@Param("incidentId") UUID incidentId, Limit limit);
}
