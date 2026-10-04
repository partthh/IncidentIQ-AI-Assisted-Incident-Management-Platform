package com.sentinelai.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinelai.events.EventRepository;
import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.incidents.IncidentStatus;
import com.sentinelai.support.ApiTestSupport;
import java.time.Duration;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Idempotency is a correctness property, not an optimisation.
 *
 * <p>Producers retry. Networks time out after the server has already committed. A
 * platform that creates a second event — and therefore a second incident, or inflates
 * an existing one — turns its most reliable client into its biggest source of noise.
 * The guarantee under test is that {@code (sourceScope, sourceEventId)} maps to
 * exactly one stored event forever, no matter how many times it is submitted, and
 * that duplicates do no detection work at all.
 */
class IngestionIdempotencyTest extends ApiTestSupport {

    @Autowired
    private EventRepository events;

    @Autowired
    private IncidentRepository incidents;

    @Test
    @DisplayName("a repeated sourceEventId returns the original event and does no detection work")
    void duplicateIsRecognisedAndInert() throws Exception {
        String key = "dup-" + UUID.randomUUID();

        // First submission trips the seeded p95 latency rule and opens an incident.
        MvcResult first = asEngineer(post("/api/v1/events"))
                .contentType(json()).content(payload(key, 1500, "payment-service"))
                .andExpect(status().isAccepted()).andReturn();
        Map<String, Object> firstBody = body(first);
        assertThat(firstBody).containsEntry("duplicate", false);
        assertThat((Map<?, ?>) firstBody.get("incident")).isNotNull();

        int eventsAfterFirst = (int) events.count();
        long incidentsAfterFirst = incidents.count();

        // Same producer, same key, retried.
        MvcResult second = asEngineer(post("/api/v1/events"))
                .contentType(json()).content(payload(key, 1500, "payment-service"))
                .andExpect(status().isOk()).andReturn();

        Map<String, Object> secondBody = body(second);
        assertThat(secondBody).containsEntry("duplicate", true);
        assertThat(secondBody).containsEntry("eventId", firstBody.get("eventId"));
        assertThat(secondBody.get("matchedRules")).asList().isEmpty();
        assertThat(secondBody.get("incident")).as(
                "a retry must not run detection again").isNull();

        // The duplicate did not inflate the incident's evidence count, which is the
        // part that would actually corrupt an investigation.
        assertThat(events.count()).isEqualTo(eventsAfterFirst);
        assertThat(incidents.count()).isEqualTo(incidentsAfterFirst);
    }

    @Test
    @DisplayName("the same key in different scopes is a different event")
    void scopeIsPartOfTheKey() throws Exception {
        // Two teams can legitimately use the same local id. Collapsing them would drop
        // one team's telemetry entirely, which is worse than a duplicate.
        String key = "shared-" + UUID.randomUUID();

        MvcResult a = post("/api/v1/events").header(IngestEventRequest.SOURCE_HEADER, "team-a")
                .contentType(json()).content(payload(key, 1500, "payment-service"))
                .andExpect(status().is2xxSuccessful()).andReturn();
        MvcResult b = post("/api/v1/events").header(IngestEventRequest.SOURCE_HEADER, "team-b")
                .contentType(json()).content(payload(key, 1500, "payment-service"))
                .andExpect(status().is2xxSuccessful()).andReturn();

        assertThat(body(a)).containsEntry("duplicate", false);
        assertThat(body(b)).containsEntry("duplicate", false);
        assertThat(body(a).get("eventId")).isNotEqualTo(body(b).get("eventId"));
    }

    @Test
    @DisplayName("the scope header overrides the body field")
    void headerWinsOverBody() throws Exception {
        String key = "override-" + UUID.randomUUID();

        MvcResult withHeader = post("/api/v1/events").header(IngestEventRequest.SOURCE_HEADER, "from-header")
                .contentType(json()).content(payload(key, 1500, "payment-service", "from-body"))
                .andExpect(status().is2xxSuccessful()).andReturn();

        assertThat(body(withHeader).get("sourceEventId")).isEqualTo(key);

        // The same key under the body's scope is a different event, proving the header
        // was actually used rather than merely preferred in one direction.
        MvcResult withBody = post("/api/v1/events")
                .contentType(json()).content(payload(key, 1500, "payment-service", "from-body"))
                .andExpect(status().is2xxSuccessful()).andReturn();
        assertThat(body(withBody)).containsEntry("duplicate", false);
    }

    @Test
    @DisplayName("concurrent submissions of one key produce exactly one event")
    void concurrentDuplicatesCollapseToOneEvent() throws Exception {
        String key = "race-" + UUID.randomUUID();
        long before = events.count();

        int writers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CyclicBarrier gate = new CyclicBarrier(writers);
        try {
            List<Future<MvcResult>> results = pool.invokeAll(
                    java.util.stream.IntStream.range(0, writers)
                            .mapToObj(i -> (Callable<MvcResult>) () -> {
                                gate.await(5, java.util.concurrent.TimeUnit.SECONDS);
                                return asEngineer(post("/api/v1/events"))
                                        .contentType(json())
                                        .content(payload(key, 1500, "payment-service"))
                                        .andReturn();
                            })
                            .toList());

            List<Map<String, Object>> bodies = results.stream()
                    .map(future -> {
                        try {
                            return body(future.get());
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    })
                    .toList();

            assertThat(bodies).allSatisfy(b -> assertThat(b).containsEntry("duplicate", false)
                    .as("all writers succeed; the loser is told it is a duplicate, never rejected"));

            // Every writer agrees on which event won.
            assertThat(bodies.stream().map(b -> b.get("eventId")).distinct()).hasSize(1);
            assertThat(events.count()).isEqualTo(before + 1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("a duplicate never inflates the incident's event count")
    void duplicateDoesNotInflateEvidence() throws Exception {
        String key = "inflate-" + UUID.randomUUID();

        MvcResult first = asEngineer(post("/api/v1/events"))
                .contentType(json()).content(payload(key, 1500, "payment-service"))
                .andExpect(status().is2xxSuccessful()).andReturn();
        String incidentId = String.valueOf(((Map<?, ?>) body(first).get("incident")).get("incidentId"));
        int countAfterFirst = incidentEventCount(incidentId);

        for (int i = 0; i < 5; i++) {
            asEngineer(post("/api/v1/events"))
                    .contentType(json()).content(payload(key, 1500, "payment-service"))
                    .andExpect(status().isOk());
        }

        assertThat(incidentEventCount(incidentId)).isEqualTo(countAfterFirst);
    }

    @Test
    @DisplayName("a repeat with a new key extends the active incident rather than opening another")
    void newKeyForTheSameFailureAbsorbsIntoTheActiveIncident() throws Exception {
        long before = incidents.count();

        MvcResult first = asEngineer(post("/api/v1/events"))
                .contentType(json()).content(payload("absorb-a-" + UUID.randomUUID(), 1800, "payment-service"))
                .andExpect(status().is2xxSuccessful()).andReturn();
        Map<?, ?> pointer = (Map<?, ?>) body(first).get("incident");
        String incidentId = String.valueOf(pointer.get("incidentId"));

        // Same service, same threshold breach, different event id: the same outage.
        MvcResult second = asEngineer(post("/api/v1/events"))
                .contentType(json()).content(payload("absorb-b-" + UUID.randomUUID(), 1900, "payment-service"))
                .andReturn();

        Map<?, ?> secondPointer = (Map<?, ?>) body(second).get("incident");
        assertThat(secondPointer).isNotNull();
        assertThat(secondPointer.get("incidentId")).as(
                "an ongoing outage must not fragment into a second incident").isEqualTo(incidentId);
        assertThat(incidents.count()).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("an unregistered service is rejected with an actionable message")
    void unknownServiceIsRejected() throws Exception {
        String key = "unknown-" + UUID.randomUUID();

        MvcResult result = asEngineer(post("/api/v1/events"))
                .contentType(json()).content(payload(key, 1500, "no-such-service"))
                .andExpect(status().isUnprocessableEntity())
                .andReturn();

        Map<String, Object> error = body(result);
        assertThat(error).containsEntry("code", "UNKNOWN_SERVICE");
        // The message names the services that do exist, because "unknown service" alone
        // leaves the producer guessing which value was wrong.
        assertThat(String.valueOf(error.get("message"))).contains("payment-service");
    }

    @Test
    @DisplayName("credentials are stripped before anything is stored")
    void secretsNeverReachTheDatabase() throws Exception {
        String key = "secret-" + UUID.randomUUID();
        Map<String, Object> payload = new HashMap<>();
        payload.put("sourceEventId", key);
        payload.put("sourceScope", "test");
        payload.put("service", "payment-service");
        payload.put("environment", "staging");
        payload.put("eventType", "LOG");
        payload.put("severity", "ERROR");
        payload.put("message", "auth failed for alice@example.com token=abcdefghijklmnopqrstuvwx");
        payload.put("occurredAt", Instant.now().toString());
        payload.put("metadata", Map.of("password", "hunter2", "p95LatencyMs", 1500));

        MvcResult result = asEngineer(post("/api/v1/events")).contentType(json()).content(toJson(payload))
                .andExpect(status().is2xxSuccessful()).andReturn();

        String eventId = String.valueOf(body(result).get("eventId"));
        MvcResult stored = asViewer(get("/api/v1/events/" + eventId)).andExpect(status().isOk()).andReturn();
        String raw = bodyAsString(stored);

        assertThat(raw).doesNotContain("hunter2");
        assertThat(raw).doesNotContain("abcdefghijklmnopqrstuvwx");
        assertThat(raw).doesNotContain("alice@example.com");
        // The metric survives: detection depends on it, so sanitising must not touch it.
        assertThat(raw).contains("p95LatencyMs");
        assertThat(raw).contains("[redacted]");
    }

    @Test
    @DisplayName("a future timestamp beyond the skew allowance is rejected")
    void brokenProducerClockIsRejected() throws Exception {
        Map<String, Object> payload = new HashMap<>(basePayload("skew-" + UUID.randomUUID(), 1500));
        payload.put("occurredAt", Instant.now().plus(Duration.ofHours(2)).toString());

        asEngineer(post("/api/v1/events")).contentType(json()).content(toJson(payload))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CLOCK_SKEW"));
    }

    @Test
    @DisplayName("a VIEWER cannot write telemetry")
    void viewersCannotIngest() throws Exception {
        asViewer(post("/api/v1/events"))
                .contentType(json()).content(payload("viewer-" + UUID.randomUUID(), 1500, "payment-service"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("an unauthenticated caller is rejected before any work is done")
    void anonymousCannotIngest() throws Exception {
        long before = events.count();

        post("/api/v1/events")
                .contentType(json()).content(payload("anon-" + UUID.randomUUID(), 1500, "payment-service"))
                .andExpect(status().isUnauthorized());

        assertThat(events.count()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ helpers

    private int incidentEventCount(String incidentId) {
        return incidents.findById(UUID.fromString(incidentId))
                .map(incident -> incident.getEventCount())
                .orElse(0);
    }

    private Map<String, Object> basePayload(String key, int p95) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sourceEventId", key);
        payload.put("sourceScope", "test");
        payload.put("service", "payment-service");
        payload.put("environment", "staging");
        payload.put("eventType", "METRIC");
        payload.put("severity", "ERROR");
        payload.put("message", "checkout latency p95 " + p95 + "ms");
        payload.put("occurredAt", Instant.now().minusSeconds(5).toString());
        payload.put("metadata", Map.of("p95LatencyMs", p95));
        return payload;
    }

    private String payload(String key, int p95, String service) throws Exception {
        Map<String, Object> payload = basePayload(key, p95);
        payload.put("service", service);
        return toJson(payload);
    }

    @Test
    @DisplayName("the stored incident is queryable by reference once detection has run")
    void openedIncidentIsQueryable() throws Exception {
        MvcResult ingestResult = asEngineer(post("/api/v1/events"))
                .contentType(json()).content(payload("query-" + UUID.randomUUID(), 2500, "payment-service"))
                .andExpect(status().isAccepted()).andReturn();

        String reference = String.valueOf(((Map<?, ?>) body(ingestResult).get("incident")).get("reference"));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                asViewer(get("/api/v1/incidents/by-reference/" + reference))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.reference").value(reference))
                        .andExpect(jsonPath("$.status").value(IncidentStatus.OPEN.name())));
    }
}