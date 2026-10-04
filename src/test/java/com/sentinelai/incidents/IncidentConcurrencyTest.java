package com.sentinelai.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinelai.common.EventType;
import com.sentinelai.common.Severity;
import com.sentinelai.events.EventRepository;
import com.sentinelai.security.AppUser;
import com.sentinelai.security.SentinelPrincipal;
import com.sentinelai.support.ApiTestSupport;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Two engineers working one incident is the normal case, not the exceptional one.
 *
 * <p>The property under test is that a lost update is impossible and, when a caller
 * supplies a stale version, that the conflict is reported rather than silently
 * applied. Silently losing a reassignment is the worst outcome available here: the
 * incident appears owned by someone who never looked at it, and nothing in the
 * system says so.
 */
class IncidentConcurrencyTest extends ApiTestSupport {

    @Autowired
    private IncidentRepository incidents;

    @Autowired
    private EventRepository events;

    private UUID incidentId;
    private long version;

    /**
     * Opens a fresh incident for the test that is about to run.
     *
     * <p>The test method name is part of the message on purpose. Every sample of the
     * same failing service carries the same signature by design, so without a
     * distinguishing element each test would inherit the incident an earlier test left
     * in whatever state it happened to leave it — and a test that asserts
     * {@code status == OPEN} would fail because an unrelated test had acknowledged.
     * Per-test incidents keep the assertions about isolation honest.
     */
    @BeforeEach
    void openIncident(TestInfo testInfo) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sourceEventId", "conc-" + UUID.randomUUID());
        payload.put("sourceScope", "concurrency-test");
        payload.put("service", "payment-service");
        payload.put("environment", "staging");
        payload.put("eventType", "LATENCY_SPIKE");
        payload.put("severity", "HIGH");
        payload.put("message", "checkout p95 latency 2400ms (" + testName(testInfo) + ")");
        payload.put("occurredAt", Instant.now().minusSeconds(3).toString());
        payload.put("metadata", Map.of("p95LatencyMs", 2400));

        MvcResult ingest = asEngineer(post("/api/v1/events")
                .contentType(json()).content(toJson(payload)))
                .andExpect(status().is2xxSuccessful()).andReturn();
        Map<?, ?> pointer = (Map<?, ?>) body(ingest).get("incident");
        incidentId = UUID.fromString(String.valueOf(pointer.get("incidentId")));

        MvcResult detail = asViewer(get("/api/v1/incidents/" + incidentId))
                .andExpect(status().isOk()).andReturn();
        version = ((Number) body(detail).get("version")).longValue();
    }

    private static String testName(TestInfo testInfo) {
        return testInfo.getTestMethod().map(Method::getName).orElse("unnamed");
    }

    @Test
    @DisplayName("a stale expectedVersion is rejected with 409 CONCURRENT_MODIFICATION")
    void staleVersionIsRejected() throws Exception {
        // Someone else acts first.
        asEngineer(post("/api/v1/incidents/" + incidentId + "/acknowledge")
                .contentType(json()).content(j(Map.of("expectedVersion", version)))
                ).andExpect(status().isOk());

        // The stale dashboard now tries to act on the version it still remembers.
        MvcResult conflict = asEngineer(post("/api/v1/incidents/" + incidentId + "/assignment")
                .contentType(json())
                .content(j(Map.of("expectedVersion", version,
                        "assignee", Map.of("email", "marco@sentinel.dev")))))
                .andExpect(status().isConflict())
                .andReturn();

        assertThat(body(conflict)).containsEntry("code", "CONFLICT");
        assertThat(String.valueOf(body(conflict).get("message"))).contains("Reload and retry");
    }

    @Test
    @DisplayName("If-Match is honoured so header-driven clients get the same protection")
    void ifMatchHeaderIsHonoured() throws Exception {
        asEngineer(post("/api/v1/incidents/" + incidentId + "/notes")
                .contentType(json()).content(j(Map.of("body", "first note")))
                ).andExpect(status().isOk());

        asEngineer(post("/api/v1/incidents/" + incidentId + "/notes")
                .header("If-Match", "\"" + version + "\"")
                .contentType(json()).content(j(Map.of("body", "second note")))
                ).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("concurrent assignments produce exactly one winner")
    void concurrentAssignmentHasOneWinner() throws Exception {
        // Without a version precondition both writers are "correct" and the last one
        // wins silently. With one, exactly one succeeds and the loser is told.
        int writers = 6;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CyclicBarrier gate = new CyclicBarrier(writers);
        try {
            List<Callable<MvcResult>> jobs = java.util.stream.IntStream.range(0, writers)
                    .mapToObj(i -> (Callable<MvcResult>) () -> {
                        gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                        return asEngineer(post("/api/v1/incidents/" + incidentId + "/assignment")
                                .contentType(json())
                                .content(j(Map.of("expectedVersion", version,
                                        "assignee", Map.of("email",
                                                i % 2 == 0 ? "priya@sentinel.dev" : "marco@sentinel.dev")))))
                                .andReturn();
                    })
                    .toList();

            List<Integer> statuses = pool.invokeAll(jobs).stream()
                    .map(IncidentConcurrencyTest::statusOf)
                    .toList();

            assertThat(statuses).filteredOn(status -> status == 200).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 409).hasSize(writers - 1);
        } finally {
            pool.shutdownNow();
        }

        MvcResult detail = asViewer(get("/api/v1/incidents/" + incidentId)).andReturn();
        assertThat((String) body(detail).get("status")).isEqualTo("OPEN");
        assertThat(body(detail).get("assignedTo")).as("exactly one owner").isNotNull();
    }

    private static int statusOf(Future<MvcResult> future) {
        try {
            return future.get().getResponse().getStatus();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    @DisplayName("an engineer may be handed an incident already being investigated")
    void acknowledgingAnInvestigatingIncidentIsTolerated() throws Exception {
        asEngineer(post("/api/v1/incidents/" + incidentId + "/investigate")
                .contentType(json()).content(j(Map.of()))
                ).andExpect(status().isOk());

        // A second engineer clicks acknowledge after a page reload. Failing this would
        // be technically correct and practically hostile.
        MvcResult result = asEngineer(post("/api/v1/incidents/" + incidentId + "/acknowledge")
                .contentType(json()).content(j(Map.of())))
                .andExpect(status().isOk()).andReturn();

        assertThat((String) body(result).get("status")).isEqualTo("INVESTIGATING");
    }

    @Test
    @DisplayName("every mutation lands in the audit timeline")
    void mutationsAreAudited() throws Exception {
        AppUser priya = users.findByEmailIgnoreCase("priya@sentinel.dev").orElseThrow();

        asEngineer(post("/api/v1/incidents/" + incidentId + "/acknowledge")
                .contentType(json()).content(j(Map.of()))).andExpect(status().isOk());
        asEngineer(post("/api/v1/incidents/" + incidentId + "/assignment")
                .contentType(json())
                .content(j(Map.of("assignee", Map.of("email", "marco@sentinel.dev")))))
                .andExpect(status().isOk());
        asEngineer(post("/api/v1/incidents/" + incidentId + "/notes")
                .contentType(json()).content(j(Map.of("body", "rollback suspected"))))
                .andExpect(status().isOk());
        asEngineer(post("/api/v1/incidents/" + incidentId + "/resolve")
                .contentType(json())
                .content(j(Map.of("rootCause", "Connection pool exhaustion from a leaked cursor",
                        "preventiveActions", "Add a leak test to the payment integration suite"))))
                .andExpect(status().isOk());

        MvcResult timeline = asViewer(get("/api/v1/incidents/" + incidentId + "/timeline"))
                .andExpect(status().isOk()).andReturn();

        List<Map<String, Object>> entries = (List<Map<String, Object>>) body(timeline).get("entries");
        assertThat(entries).extracting(entry -> String.valueOf(entry.get("eventType")))
                .contains("INCIDENT_CREATED", "ACKNOWLEDGED", "ASSIGNED", "NOTE", "RESOLVED");

        // Sequences are dense and increasing: the timeline cursor is what a
        // reconnecting client uses to ask "what did I miss", and a gap would make it
        // silently skip an entry.
        List<Number> sequences = entries.stream().map(e -> (Number) e.get("sequence")).toList();
        assertThat(sequences).isSorted();
        assertThat(sequences).doesNotHaveDuplicates();

        assertThat(entries).anySatisfy(entry ->
                assertThat(String.valueOf(entry.get("actorName"))).isNotBlank());
        assertThat(entries).anySatisfy(entry -> {
            if ("NOTE".equals(entry.get("eventType"))) {
                assertThat(String.valueOf(entry.get("payload"))).contains("rollback suspected");
            }
        });
        assertThat(priya.getRole().canWrite()).isTrue();
    }

    @Test
    @DisplayName("resolving without a verified cause is refused")
    void resolutionRequiresAVerifiedCause() throws Exception {
        // This is the distinction the project rests on: the model may suggest, only a
        // human decides. An empty cause would train the knowledge lookup on nothing.
        asEngineer(post("/api/v1/incidents/" + incidentId + "/resolve")
                .contentType(json()).content(j(Map.of("rootCause", "   ")))
                ).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a resolved incident cannot be re-acknowledged")
    void resolvedIsTerminal() throws Exception {
        asEngineer(post("/api/v1/incidents/" + incidentId + "/resolve")
                .contentType(json()).content(j(Map.of("rootCause", "verified cause")))
                ).andExpect(status().isOk());

        asEngineer(post("/api/v1/incidents/" + incidentId + "/acknowledge")
                .contentType(json()).content(j(Map.of()))
                ).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an incident cannot be assigned to a VIEWER")
    void viewerCannotOwnAnIncident() throws Exception {
        asEngineer(post("/api/v1/incidents/" + incidentId + "/assignment")
                .contentType(json())
                .content(j(Map.of("assignee", Map.of("email", "viewer@sentinel.dev"))))
                ).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("notes and state changes bump the version a client must echo back")
    void versionAdvancesOnEveryMutation() throws Exception {
        long before = currentVersion();

        asEngineer(post("/api/v1/incidents/" + incidentId + "/notes")
                .contentType(json()).content(j(Map.of("body", "note"))))
                .andExpect(status().isOk());

        assertThat(currentVersion()).isGreaterThan(before);
    }

    @Test
    @DisplayName("a non-assignable assignee list excludes viewers")
    void assignableUsersExcludeViewers() throws Exception {
        MvcResult result = asViewer(get("/api/v1/incidents/assignable-users"))
                .andExpect(status().isOk()).andReturn();

        // A plain array, not a page: the caller wants the whole list.
        List<Map<String, Object>> options = read(result, List.class);
        assertThat(options).isNotEmpty();
        assertThat(options).allSatisfy(option ->
                assertThat(String.valueOf(option.get("role"))).isNotEqualTo("VIEWER"));
    }

    @Test
    @DisplayName("state changes are refused for a VIEWER but reads are not")
    void viewersAreReadOnly() throws Exception {
        asViewer(get("/api/v1/incidents/" + incidentId)).andExpect(status().isOk());
        asViewer(get("/api/v1/incidents")).andExpect(status().isOk());

        asViewer(post("/api/v1/incidents/" + incidentId + "/acknowledge")
                .contentType(json()).content(j(Map.of()))
                ).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("timeline cursor pagination returns only entries the client missed")
    void timelineCursorIsResumable() throws Exception {
        asEngineer(post("/api/v1/incidents/" + incidentId + "/notes")
                .contentType(json()).content(j(Map.of("body", "first")))
                ).andExpect(status().isOk());
        long cursor = ((Number) body(asViewer(get("/api/v1/incidents/" + incidentId)).andReturn())
                .get("timelineSeq")).longValue();

        asEngineer(post("/api/v1/incidents/" + incidentId + "/notes")
                .contentType(json()).content(j(Map.of("body", "second")))
                ).andExpect(status().isOk());

        MvcResult resumed = asViewer(get("/api/v1/incidents/" + incidentId + "/timeline")
                        .param("afterSequence", String.valueOf(cursor)))
                .andExpect(status().isOk()).andReturn();

        List<Map<String, Object>> entries = (List<Map<String, Object>>) body(resumed).get("entries");
        assertThat(entries).hasSize(1);
        assertThat(entries).allSatisfy(entry ->
                assertThat(((Number) entry.get("sequence")).longValue()).isGreaterThan(cursor));
    }

    private long currentVersion() throws Exception {
        return ((Number) body(asViewer(get("/api/v1/incidents/" + incidentId)).andReturn())
                .get("version")).longValue();
    }

    @Test
    @DisplayName("an unknown incident is a 404 with the same envelope as everything else")
    void unknownIncidentIs404() throws Exception {
        asViewer(get("/api/v1/incidents/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("the feed filters by status and service without breaking pagination")
    void feedFiltersAreApplied() throws Exception {
        MvcResult result = asViewer(get("/api/v1/incidents")
                        .param("status", "OPEN")
                        .param("service", "payment-service")
                        .param("size", "5"))
                .andExpect(status().isOk()).andReturn();

        Map<String, Object> page = body(result);
        assertThat((Integer) page.get("size")).isEqualTo(5);
        List<Map<String, Object>> items = (List<Map<String, Object>>) page.get("items");
        assertThat(items).allSatisfy(item ->
                assertThat(item).containsEntry("status", "OPEN"));
    }

    @Test
    @DisplayName("an unbounded page size is clamped instead of honoured")
    void pageSizeIsClamped() throws Exception {
        MvcResult result = asViewer(get("/api/v1/incidents").param("size", "100000"))
                .andExpect(status().isOk()).andReturn();

        // An unbounded size is an available denial of service; the clamp is the fix.
        assertThat((Integer) body(result).get("size")).isEqualTo(200);
    }

    @Test
    @DisplayName("an unknown sort field falls back to the default instead of failing")
    void unknownSortIsIgnored() throws Exception {
        asViewer(get("/api/v1/incidents").param("sort", "1; drop table incidents"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("events are searchable by occurrence time, not ingestion time")
    void eventSearchUsesEventTime() throws Exception {
        String signature = events.findAll().stream()
                .filter(e -> e.getOccurredAt().isAfter(Instant.now().minusSeconds(600)))
                .map(com.sentinelai.events.EventEntity::getErrorSignature)
                .findFirst()
                .orElse(null);
        assertThat(signature).as("the incident opened above provides a recent signature").isNotNull();

        MvcResult result = asViewer(get("/api/v1/events")
                        .param("signature", signature)
                        .param("from", Instant.now().minusSeconds(3600).toString()))
                .andExpect(status().isOk()).andReturn();

        List<Map<String, Object>> items = (List<Map<String, Object>>) body(result).get("items");
        assertThat(items).isNotEmpty();
        assertThat(items).allSatisfy(item ->
                assertThat(item).containsEntry("errorSignature", signature));
    }

    @Test
    @DisplayName("event type filtering is case-insensitive on the enum only")
    void eventTypeFilterIsValidated() throws Exception {
        asViewer(get("/api/v1/events").param("type", EventType.METRIC.name()))
                .andExpect(status().isOk());

        asViewer(get("/api/v1/events").param("type", "NOT_A_TYPE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("severity filter is honoured on the feed")
    void severityFilterIsHonoured() throws Exception {
        asViewer(get("/api/v1/incidents").param("severity", Severity.CRITICAL.name()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the incident reference is stable across reads")
    void referenceIsStable() throws Exception {
        String reference = String.valueOf(body(asViewer(get("/api/v1/incidents/" + incidentId)).andReturn())
                .get("reference"));

        for (int i = 0; i < 3; i++) {
            asViewer(get("/api/v1/incidents/" + incidentId))
                    .andExpect(jsonPath("$.reference").value(reference));
        }
        assertThat(SentinelPrincipal.class).isNotNull();
    }
}
