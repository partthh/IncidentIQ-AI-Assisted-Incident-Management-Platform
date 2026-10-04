package com.sentinelai.investigation;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiAnalysisRepository extends JpaRepository<AiAnalysisEntity, UUID>,
        JpaSpecificationExecutor<AiAnalysisEntity> {

    Optional<AiAnalysisEntity> findByReference(String reference);

    Optional<AiAnalysisEntity> findFirstByIncidentIdOrderByCreatedAtDesc(UUID incidentId);

    Optional<AiAnalysisEntity> findFirstByIncidentIdAndStatusOrderByCreatedAtDesc(
            UUID incidentId, AnalysisStatus status);

    List<AiAnalysisEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId, Limit limit);

    List<AiAnalysisEntity> findByStatusOrderByCreatedAtAsc(AnalysisStatus status, Limit limit);

    long countByStatus(AnalysisStatus status);

    /**
     * Does this incident already have work in flight?
     *
     * <p>Used to keep repeated occurrences of one noisy problem from queueing a
     * request per event. The check and the insert share the caller's transaction so
     * the incident's row lock serialises concurrent triggers.
     */
    @Query("""
            select case when count(a) > 0 then true else false end
            from AiAnalysisEntity a
            where a.incidentId = :incidentId and a.status in :statuses
            """)
    boolean existsPendingForIncident(@Param("incidentId") UUID incidentId,
                                     @Param("statuses") List<AnalysisStatus> statuses);

    long countByIncidentId(UUID incidentId);

    Page<AiAnalysisEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AiAnalysisEntity> findByIncidentIdOrderByCreatedAtDesc(UUID incidentId, Pageable pageable);

    /**
     * Claims a queued row under a write lock.
     *
     * <p>The lock is what makes the claim atomic: the processor checks for a
     * {@code QUEUED} row, then re-reads it locked and re-checks the status before
     * flipping it to {@code RUNNING}. Without the second check two pollers — or a
     * poller and a manual retry — would both believe they own the same job and bill
     * the provider for it twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from AiAnalysisEntity a where a.id = :id")
    Optional<AiAnalysisEntity> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Abandoned {@code RUNNING} rows, found by age rather than by a heartbeat column.
     *
     * <p>A crash mid-analysis leaves a row that no worker will ever finish. Without
     * this the job is lost silently and the incident never gets the investigation it
     * was promised; with it, the next poll picks the work up again.
     */
    @Query("""
            select a from AiAnalysisEntity a
            where a.status = com.sentinelai.investigation.AnalysisStatus.RUNNING
              and a.startedAt < :cutoff
            order by a.startedAt asc
            """)
    List<AiAnalysisEntity> findStaleRunning(@Param("cutoff") Instant cutoff, Limit limit);

    /** Mean end-to-end duration of successful analyses, for the metrics endpoint. */
    @Query("""
            select avg(a.durationMs) from AiAnalysisEntity a
            where a.status = com.sentinelai.investigation.AnalysisStatus.COMPLETED
            """)
    Double averageCompletedDurationMs();
}