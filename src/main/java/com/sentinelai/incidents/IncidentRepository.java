package com.sentinelai.incidents;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IncidentRepository extends JpaRepository<IncidentEntity, UUID>,
        JpaSpecificationExecutor<IncidentEntity> {

    Optional<IncidentEntity> findByReference(String reference);

    /**
     * Feed queries fetch the service in one round trip.
     *
     * <p>Every dashboard row needs its service, and a lazy proxy on a paged result
     * would turn one query into N+1 on the hottest endpoint in the application.
     * Overriding the executor's method keeps that fix in the repository rather than
     * in each caller.
     */
    @Override
    @EntityGraph(attributePaths = "service")
    Page<IncidentEntity> findAll(Specification<IncidentEntity> spec, Pageable pageable);

    /**
     * Single-incident loads fetch the service too.
     *
     * <p>Not an optimisation here but a correctness requirement: the API layer reads
     * {@code service} after the repository call has closed its transaction, so a lazy
     * proxy would throw {@code LazyInitializationException} on a detached entity. Any
     * new caller of {@code findById} gets a usable incident instead of a trap.
     */
    @Override
    @EntityGraph(attributePaths = "service")
    Optional<IncidentEntity> findById(UUID id);

    /**
     * Same reason as {@link #findById}: controllers enrich rows with the service name
     * after the transaction ends, and one join beats N+1 for the batch.
     */
    @Override
    @EntityGraph(attributePaths = "service")
    List<IncidentEntity> findAllById(Iterable<UUID> ids);

    /** Active = not RESOLVED. Enforced in the query rather than in Java so it stays correct. */
    @Query("""
            select i from IncidentEntity i
            where i.fingerprint = :fingerprint and i.status <> com.sentinelai.incidents.IncidentStatus.RESOLVED
            order by i.lastSeenAt desc
            """)
    List<IncidentEntity> findActiveByFingerprint(@Param("fingerprint") String fingerprint, Limit limit);

    @Query("""
            select i from IncidentEntity i
            join fetch i.service
            where i.fingerprint = :fingerprint and i.status <> com.sentinelai.incidents.IncidentStatus.RESOLVED
            order by i.lastSeenAt desc
            """)
    List<IncidentEntity> findActiveByFingerprintFetchService(@Param("fingerprint") String fingerprint, Limit limit);

    Page<IncidentEntity> findAllByOrderByLastSeenAtDesc(Pageable pageable);

    Page<IncidentEntity> findByStatusOrderByLastSeenAtDesc(IncidentStatus status, Pageable pageable);

    @Query("""
            select count(i) from IncidentEntity i
            where i.status <> com.sentinelai.incidents.IncidentStatus.RESOLVED
            """)
    long countActive();

    @Query("""
            select count(i) from IncidentEntity i
            where i.status = com.sentinelai.incidents.IncidentStatus.RESOLVED
            """)
    long countResolved();

    long countByStatus(IncidentStatus status);

    long countBySeverityAndStatus(com.sentinelai.common.Severity severity, IncidentStatus status);

    @Query("""
            select i.service.name, count(i) from IncidentEntity i
            where i.status <> com.sentinelai.incidents.IncidentStatus.RESOLVED
            group by i.service.name
            order by count(i) desc
            """)
    List<Object[]> countActiveByServiceName();

    /** Verified history used by the AI knowledge lookup and the postmortem search. */
    @Query("""
            select i from IncidentEntity i
            join fetch i.service
            where i.status = com.sentinelai.incidents.IncidentStatus.RESOLVED
              and i.service.id = :serviceId
              and i.resolvedAt is not null
              and (:after is null or i.resolvedAt >= :after)
            order by i.resolvedAt desc
            """)
    List<IncidentEntity> findResolvedHistory(@Param("serviceId") UUID serviceId,
                                             @Param("after") Instant after,
                                             Limit limit);

    @Query("select i.timelineSeq from IncidentEntity i where i.id = :id")
    Optional<Long> findTimelineSeq(@Param("id") UUID id);

    /**
     * Loads an incident with a row lock held for the rest of the transaction.
     *
     * <p>Every mutation takes this lock before touching the incident. It
     * serialises concurrent engineers on one incident, and it makes allocating
     * the next timeline sequence safe: two callers cannot compute the same value.
     * Optimistic locking via {@code @Version} remains in place as a second line
     * of defence for callers that mutate a detached instance.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from IncidentEntity i where i.id = :id")
    Optional<IncidentEntity> findByIdForUpdate(@Param("id") UUID id);

    List<IncidentEntity> findByCorrelationGroupAndStatusNotOrderByLastSeenAtDesc(
            String correlationGroup, IncidentStatus excludedStatus, Limit limit);

    List<IncidentEntity> findByFingerprintAndStatusOrderByLastSeenAtDesc(
            String fingerprint, IncidentStatus status, Limit limit);

    long countByServiceIdAndStatusNot(UUID serviceId, IncidentStatus excludedStatus);

    /** Verified history: resolved, with a recorded resolution time, for knowledge lookup. */
    List<IncidentEntity> findByServiceIdAndStatusAndResolvedAtAfterOrderByResolvedAtDesc(
            UUID serviceId, IncidentStatus status, Instant resolvedAfter, Limit limit);
}
