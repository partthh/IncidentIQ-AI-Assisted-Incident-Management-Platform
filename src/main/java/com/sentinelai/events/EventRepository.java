package com.sentinelai.events;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<EventEntity, UUID>,
        JpaSpecificationExecutor<EventEntity> {

    Optional<EventEntity> findBySourceScopeAndSourceEventId(String sourceScope, String sourceEventId);

    /** Search views need the service name on every row; fetch it rather than proxy it. */
    @Override
    @EntityGraph(attributePaths = "service")
    Page<EventEntity> findAll(Specification<EventEntity> spec, Pageable pageable);

    /**
     * Loads an event with its service initialised.
     *
     * <p>Detection runs in a transaction separate from the one that stored the
     * event, so the entity it receives is detached. Fetching the service eagerly
     * here keeps that hand-off safe: a lazy proxy on a detached entity would throw
     * on first access, deep inside the detection transaction.
     */
    @Query("select e from EventEntity e join fetch e.service where e.id = :id")
    Optional<EventEntity> findByIdWithService(@Param("id") UUID id);

    /**
     * Evidence retrieval for AI context: most recent events for a service
     * inside a time window, newest first.
     */
    @Query("""
            select e from EventEntity e
            where e.service.id = :serviceId
              and e.occurredAt between :from and :to
            order by e.occurredAt desc
            """)
    List<EventEntity> findForServiceWindow(@Param("serviceId") UUID serviceId,
                                           @Param("from") Instant from,
                                           @Param("to") Instant to,
                                           Limit limit);
}
