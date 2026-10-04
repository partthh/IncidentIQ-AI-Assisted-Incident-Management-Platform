package com.sentinelai.incidents;

import com.sentinelai.common.PageResponse;
import com.sentinelai.common.Severity;
import com.sentinelai.incidents.IncidentViews.Summary;
import com.sentinelai.security.AppUser;
import com.sentinelai.security.AppUserRepository;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the incident feed.
 *
 * <p>Filters are assembled into a {@link Specification} rather than a fixed set of
 * repository methods. The combinatorial alternative — one derived query per
 * status/severity/service/assignee combination — is a class that must be edited every
 * time a filter is added, and where forgetting one combination produces a silently
 * wrong result set rather than a compile error.
 *
 * <p>Assignee names are resolved in one query for the whole page rather than per row.
 * The dashboard renders twenty rows; twenty individual lookups would be twenty round
 * trips to show a name that is already known.
 */
@Service
public class IncidentQueryService {

    private final IncidentRepository incidents;
    private final AppUserRepository users;

    public IncidentQueryService(IncidentRepository incidents, AppUserRepository users) {
        this.incidents = incidents;
        this.users = users;
    }

    /** Every filter the feed supports. All optional. */
    public record Filter(
            IncidentStatus status,
            Severity severity,
            List<IncidentStatus> statuses,
            UUID serviceId,
            String serviceName,
            UUID assignedTo,
            Boolean unassigned,
            String search,
            Instant since,
            Instant until,
            boolean activeOnly
    ) {
        public static Filter empty() {
            return new Filter(null, null, null, null, null, null, null, null, null, null, false);
        }
    }

    @Transactional(readOnly = true)
    public PageResponse<Summary> search(Filter filter, Pageable pageable) {
        Page<IncidentEntity> page = incidents.findAll(toSpecification(filter), pageable);
        Map<UUID, AppUser> assignees = resolveAssignees(page.getContent());
        return PageResponse.from(page, incident -> Summary.from(incident,
                assignees.get(incident.getAssignedTo()) == null ? null
                        : new IncidentViews.AssigneeRef(
                                assignees.get(incident.getAssignedTo()).getId(),
                                assignees.get(incident.getAssignedTo()).getName(),
                                assignees.get(incident.getAssignedTo()).getEmail())));
    }

    /**
     * Builds the predicate.
     *
     * <p>Every branch is additive and optional, so an unset filter contributes nothing
     * rather than a restrictive default. That keeps "no filter" and "explicitly empty"
     * from being conflated, which is the usual way filtered lists start lying.
     */
    Specification<IncidentEntity> toSpecification(Filter filter) {
        return (root, query, builder) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (filter.activeOnly()) {
                predicates.add(builder.notEqual(root.get("status"), IncidentStatus.RESOLVED));
            }
            if (filter.status() != null) {
                predicates.add(builder.equal(root.get("status"), filter.status()));
            }
            if (filter.statuses() != null && !filter.statuses().isEmpty()) {
                predicates.add(root.get("status").in(filter.statuses()));
            }
            if (filter.severity() != null) {
                predicates.add(builder.equal(root.get("severity"), filter.severity()));
            }
            if (filter.serviceId() != null) {
                predicates.add(builder.equal(root.get("service").get("id"), filter.serviceId()));
            }
            if (filter.serviceName() != null && !filter.serviceName().isBlank()) {
                predicates.add(builder.equal(builder.lower(root.get("service").get("name")),
                        filter.serviceName().trim().toLowerCase(java.util.Locale.ROOT)));
            }
            if (filter.assignedTo() != null) {
                predicates.add(builder.equal(root.get("assignedTo"), filter.assignedTo()));
            }
            if (Boolean.TRUE.equals(filter.unassigned())) {
                predicates.add(builder.isNull(root.get("assignedTo")));
            }
            if (filter.since() != null) {
                // Filters on last activity, not creation: an incident opened last week
                // and still failing is what an engineer is looking for.
                predicates.add(builder.greaterThanOrEqualTo(root.get("lastSeenAt"), filter.since()));
            }
            if (filter.until() != null) {
                predicates.add(builder.lessThanOrEqualTo(root.get("lastSeenAt"), filter.until()));
            }
            if (filter.search() != null && !filter.search().isBlank()) {
                String needle = "%" + filter.search().trim().toLowerCase(java.util.Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), needle),
                        builder.like(builder.lower(root.get("reference")), needle),
                        builder.like(builder.lower(root.get("errorSignature")), needle)));
            }

            return predicates.isEmpty() ? builder.conjunction() : builder.and(predicates.toArray(new Predicate[0]));
        };
    }

    /**
     * Loads every referenced assignee in one query.
     *
     * <p>Deduplicated by id, so twenty rows assigned to two people cost two lookups
     * rather than twenty.
     */
    private Map<UUID, AppUser> resolveAssignees(List<IncidentEntity> content) {
        List<UUID> ids = content.stream()
                .map(IncidentEntity::getAssignedTo)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, AppUser> byId = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            users.findAllById(ids).forEach(user -> byId.put(user.getId(), user));
        }
        return byId;
    }
}