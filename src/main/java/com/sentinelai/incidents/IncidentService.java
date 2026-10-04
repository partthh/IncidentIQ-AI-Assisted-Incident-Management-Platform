package com.sentinelai.incidents;

import com.sentinelai.common.ConflictException;
import com.sentinelai.common.ForbiddenActionException;
import com.sentinelai.common.NotFoundException;
import com.sentinelai.incidents.IncidentViews.AssigneeRef;
import com.sentinelai.incidents.IncidentViews.Detail;
import com.sentinelai.incidents.IncidentViews.Summary;
import com.sentinelai.realtime.AfterCommitExecutor;
import com.sentinelai.realtime.IncidentUpdatePublisher;
import com.sentinelai.realtime.RealtimeEventType;
import com.sentinelai.security.AppUser;
import com.sentinelai.security.AppUserRepository;
import com.sentinelai.security.SentinelPrincipal;
import com.sentinelai.timeline.TimelineEventTypes;
import com.sentinelai.timeline.TimelineService;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The engineer-facing side of an incident: acknowledge, assign, comment, resolve.
 *
 * <p>Every mutation follows the same three steps, and the ordering is the point:
 * <ol>
 *   <li>take the incident's row lock,</li>
 *   <li>validate the requested transition and the caller's version,</li>
 *   <li>change state and append the audit entry in the same transaction.</li>
 * </ol>
 *
 * <p>Two independent guards prevent lost updates. The pessimistic lock serialises
 * concurrent writers; the {@code expectedVersion} check is what makes a stale
 * dashboard fail loudly. Optimistic locking alone would let the second writer's
 * read-modify-write succeed against a stale base if its transaction had not yet
 * touched the row, which is exactly the race that silently reassigns an incident
 * out from under an engineer who is reading it.
 */
@Service
public class IncidentService {

    private static final Logger log = LoggerFactory.getLogger(IncidentService.class);

    private final IncidentRepository incidents;
    private final AppUserRepository users;
    private final TimelineService timeline;
    private final IncidentUpdatePublisher publisher;

    public IncidentService(IncidentRepository incidents, AppUserRepository users, TimelineService timeline,
                           IncidentUpdatePublisher publisher) {
        this.incidents = incidents;
        this.users = users;
        this.timeline = timeline;
        this.publisher = publisher;
    }

    @Transactional(readOnly = true)
    public Detail getDetail(UUID incidentId) {
        IncidentEntity incident = incidents.findById(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));
        return Detail.from(incident, assigneeOf(incident));
    }

    @Transactional(readOnly = true)
    public Summary getSummary(UUID incidentId) {
        IncidentEntity incident = incidents.findById(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));
        return Summary.from(incident, assigneeOf(incident));
    }

    @Transactional
    public Detail acknowledge(UUID incidentId, SentinelPrincipal actor, Long expectedVersion) {
        IncidentEntity incident = lock(incidentId);
        verifyVersion(incident, expectedVersion);
        if (incident.getStatus() == IncidentStatus.RESOLVED) {
            throw new ForbiddenActionException("Incident " + incident.getReference() + " is already resolved");
        }
        IncidentStateMachine.require(incident.getStatus(), IncidentStatus.ACKNOWLEDGED);

        // Ask the state machine rather than comparing statuses here: acknowledging an
        // incident that is already under active investigation is a tolerated no-op, and
        // writing ACKNOWLEDGED anyway would silently report that nobody is working a
        // problem someone is working.
        if (!IncidentStateMachine.isIdempotentNoOp(incident.getStatus(), IncidentStatus.ACKNOWLEDGED)) {
            incident.changeStatus(IncidentStatus.ACKNOWLEDGED);
            timeline.append(incident, actor.userId(), TimelineEventTypes.ACKNOWLEDGED,
                    actor.name() + " acknowledged the incident", Map.of("status", "ACKNOWLEDGED"));
            publishAfterCommit(incident, RealtimeEventType.INCIDENT_ACKNOWLEDGED, incident.getTimelineSeq(),
                    snapshot(incident, actor.name() + " acknowledged the incident"));
        }
        return Detail.from(incident, assigneeOf(incident));
    }

    /**
     * Moves an acknowledged incident into active investigation. Kept as an explicit
     * step rather than folded into acknowledge so the dashboard can show whether
     * someone is actually looking at a problem.
     */
    @Transactional
    public Detail startInvestigating(UUID incidentId, SentinelPrincipal actor, Long expectedVersion) {
        IncidentEntity incident = lock(incidentId);
        verifyVersion(incident, expectedVersion);
        IncidentStateMachine.require(incident.getStatus(), IncidentStatus.INVESTIGATING);

        incident.changeStatus(IncidentStatus.INVESTIGATING);
        timeline.append(incident, actor.userId(), TimelineEventTypes.INVESTIGATING,
                actor.name() + " started investigating", Map.of("status", "INVESTIGATING"));
        publishAfterCommit(incident, RealtimeEventType.INCIDENT_INVESTIGATING, incident.getTimelineSeq(),
                snapshot(incident, actor.name() + " started investigating"));
        return Detail.from(incident, assigneeOf(incident));
    }

    /**
     * Assigns an owner. Assignment is not a status transition — an incident can be
     * assigned while OPEN — so it is authorised rather than state-checked.
     */
    @Transactional
    public Detail assign(UUID incidentId, AssigneeRef requested, Long expectedVersion, SentinelPrincipal actor) {
        IncidentEntity incident = lock(incidentId);
        verifyVersion(incident, expectedVersion);

        UUID previous = incident.getAssignedTo();
        UUID next = null;
        String summary;
        Map<String, Object> payload = new LinkedHashMap<>();

        if (requested == null) {
            summary = actor.name() + " unassigned the incident";
            payload.put("assignedTo", "");
        } else {
            AppUser assignee = findAssignee(requested);
            if (!assignee.getRole().canWrite()) {
                // VIEWERs cannot own work; silently accepting the assignment would
                // create an incident nobody can action.
                throw new ForbiddenActionException(
                        assignee.getEmail() + " has role " + assignee.getRole() + " and cannot own incidents");
            }
            next = assignee.getId();
            summary = actor.name() + " assigned the incident to " + assignee.getName();
            payload.put("assignedTo", assignee.getEmail());
            payload.put("assignedToName", assignee.getName());
        }
        payload.put("previousAssignee", previous == null ? "" : previous.toString());

        incident.assignTo(next);
        timeline.append(incident, actor.userId(), TimelineEventTypes.ASSIGNED, summary, payload);

        Map<String, Object> snapshot = snapshot(incident, summary);
        publishAfterCommit(incident, RealtimeEventType.INCIDENT_ASSIGNED, incident.getTimelineSeq(), snapshot);
        if (next != null) {
            // Personal notification: the assignee should learn about it without
            // watching the global feed.
            publishUserNotification(next, incident, snapshot);
        }
        return Detail.from(incident, assigneeOf(incident));
    }

    @Transactional
    public Detail addNote(UUID incidentId, String body, Long expectedVersion, SentinelPrincipal actor) {
        IncidentEntity incident = lock(incidentId);
        verifyVersion(incident, expectedVersion);

        String trimmed = body == null ? "" : body.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("A note cannot be empty");
        }
        long sequence = timeline.append(incident, actor.userId(), TimelineEventTypes.NOTE,
                actor.name() + " added a note", Map.of("note", trimmed, "author", actor.email()));

        // A note does not change incident state, but collaborators watching the
        // detail view should still see it appear.
        publishAfterCommit(incident, RealtimeEventType.TIMELINE_ENTRY_ADDED, sequence,
                Map.of("incidentId", incidentId.toString(), "note", trimmed, "author", actor.email(),
                        "sequence", sequence));

        incidents.save(incident);
        return Detail.from(incident, assigneeOf(incident));
    }

    /**
     * Resolves an incident and records the verified cause.
     *
     * <p>A root cause is required. That is the distinction the whole project turns
     * on: model output is a hypothesis, and only a human closing the incident can
     * promote one to a verified finding. Without a cause the system would learn
     * nothing from the incident.
     */
    @Transactional
    public Detail resolve(UUID incidentId, String rootCause, String preventiveActions, Long expectedVersion,
                          SentinelPrincipal actor) {
        IncidentEntity incident = lock(incidentId);
        verifyVersion(incident, expectedVersion);
        IncidentStateMachine.require(incident.getStatus(), IncidentStatus.RESOLVED);

        String cause = rootCause == null ? "" : rootCause.trim();
        if (cause.isEmpty()) {
            throw new IllegalArgumentException(
                    "A verified root cause is required to resolve an incident");
        }

        Instant now = Instant.now();
        incident.changeStatus(IncidentStatus.RESOLVED);
        incident.markResolved(now);
        incident.recordResolution(cause, preventiveActions == null ? null : preventiveActions.trim());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rootCause", cause);
        if (preventiveActions != null && !preventiveActions.isBlank()) {
            payload.put("preventiveActions", preventiveActions.trim());
        }
        timeline.append(incident, actor.userId(), TimelineEventTypes.RESOLVED,
                actor.name() + " resolved the incident", payload);

        Map<String, Object> snapshot = snapshot(incident, actor.name() + " resolved the incident");
        snapshot.put("rootCause", cause);
        publishAfterCommit(incident, RealtimeEventType.INCIDENT_RESOLVED, incident.getTimelineSeq(), snapshot);

        if (incident.getAssignedTo() != null) {
            publishUserNotification(incident.getAssignedTo(), incident, snapshot);
        }
        return Detail.from(incident, assigneeOf(incident));
    }

    private IncidentEntity lock(UUID incidentId) {
        return incidents.findByIdForUpdate(incidentId)
                .orElseThrow(() -> NotFoundException.of("Incident", incidentId));
    }

    private void verifyVersion(IncidentEntity incident, Long expectedVersion) {
        if (expectedVersion == null) {
            return;
        }
        if (!expectedVersion.equals(incident.getVersion())) {
            throw new ConflictException("Incident " + incident.getReference()
                    + " has changed since you loaded it (expected version " + expectedVersion
                    + ", current " + incident.getVersion() + "). Reload and retry.");
        }
    }

    private AppUser findAssignee(AssigneeRef requested) {
        if (requested.id() != null) {
            return users.findById(requested.id())
                    .orElseThrow(() -> NotFoundException.of("User", requested.id()));
        }
        return users.findByEmailIgnoreCase(requested.email() == null ? "" : requested.email())
                .orElseThrow(() -> NotFoundException.of("User", requested.email()));
    }

    private AssigneeRef assigneeOf(IncidentEntity incident) {
        if (incident.getAssignedTo() == null) {
            return null;
        }
        return users.findById(incident.getAssignedTo())
                .map(user -> new AssigneeRef(user.getId(), user.getName(), user.getEmail()))
                .orElse(null);
    }

    private Map<String, Object> snapshot(IncidentEntity incident, String note) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("incidentId", incident.getId().toString());
        payload.put("reference", incident.getReference());
        payload.put("title", incident.getTitle());
        payload.put("status", incident.getStatus().name());
        payload.put("severity", incident.getSeverity().name());
        payload.put("service", incident.getService().getQualifiedName());
        payload.put("eventCount", incident.getEventCount());
        payload.put("version", incident.getVersion());
        if (note != null) {
            payload.put("note", note);
        }
        return payload;
    }

    private void publishAfterCommit(IncidentEntity incident, RealtimeEventType type, long sequence,
                                    Map<String, Object> payload) {
        AfterCommitExecutor.run(() -> {
            publisher.publishFeed(type, incident.getId(), incident.getReference(), sequence, payload);
            publisher.publishIncident(type, incident.getId(), incident.getReference(), sequence, payload);
        });
    }

    private void publishUserNotification(UUID userId, IncidentEntity incident, Map<String, Object> payload) {
        AfterCommitExecutor.run(() -> publisher.notifyUser(userId,
                RealtimeEventType.INCIDENT_ASSIGNED, incident.getId(), incident.getReference(),
                incident.getTimelineSeq(), payload));
    }

    /** Exposed for the incident query endpoint. */
    @Transactional(readOnly = true)
    public Optional<IncidentEntity> findByReference(String reference) {
        return incidents.findByReference(reference);
    }
}
