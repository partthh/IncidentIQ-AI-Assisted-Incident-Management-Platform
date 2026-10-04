package com.sentinelai.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sentinelai.support.ApiTestSupport;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Tests for what the API says when it cannot help.
 *
 * <p>These are worth their own file because they share one property: each one asserts
 * that the response tells the caller <em>which mistake they made</em>. A 400 that says
 * "malformed body" when the body was fine and one enum value was wrong is a worse
 * answer than no answer, because it points the reader at the wrong file.
 */
class ApiDiagnosticsTest extends ApiTestSupport {

    @Test
    @DisplayName("an unknown path is a 404, not a 500")
    void unknownPathIsNotFound() throws Exception {
        // Nothing maps this URL, so it reaches the static-resource handler and throws.
        // Unhandled, that becomes a 500 with a stack trace — which reads as "the server
        // is broken" for what is a mistyped path.
        asViewer(get("/api/v1/incidents/{id}/nonsense", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("an unknown top-level path is a 404")
    void unknownRootIsNotFound() throws Exception {
        asViewer(get("/api/v1/no-such-collection"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("a bad enum value names the field and lists the legal ones")
    void enumFailureNamesTheField() throws Exception {
        Map<String, Object> details = detailsOf(invalidEvent("severity", "URGENT"));
        assertThat(details).containsKey("severity");

        // The whole point: a producer who sends "URGENT" must be able to fix it without
        // reading this source file.
        assertThat(message(details, "severity")).contains("INFO", "CRITICAL", "URGENT");
    }

    @Test
    @DisplayName("a bad timestamp is reported against occurredAt, not the whole body")
    void timestampFailureNamesTheField() throws Exception {
        Map<String, Object> details = detailsOf(invalidEvent("occurredAt", "last tuesday"));
        assertThat(details).containsKey("occurredAt");

        assertThat(message(details, "occurredAt")).contains("Instant");
    }

    @Test
    @DisplayName("genuinely broken JSON still says so")
    void brokenJsonIsMalformed() throws Exception {
        // The counterpart to the cases above. If a conversion failure were allowed to
        // report MALFORMED_REQUEST, this assertion is what keeps the distinction
        // honest: some requests really are unreadable.
        asEngineer(post("/api/v1/events").contentType(json()).content("{\"service\": "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    @DisplayName("an echoed client value is truncated rather than reflected whole")
    void echoedValuesAreBounded() throws Exception {
        // The error message quotes what the client sent. A 4KB "severity" must not come
        // back as a 4KB error body — that is a reflection amplifier, not a diagnostic.
        String absurd = "X".repeat(4000);
        Map<String, Object> details = detailsOf(invalidEvent("severity", absurd));

        assertThat(message(details, "severity"))
                .hasSizeLessThan(200)
                .startsWith("'XXX")
                .contains("…")
                .contains("severity");
    }

    @Test
    @DisplayName("every error carries a traceId, so a report can be tied to a log line")
    void errorsCarryATraceId() throws Exception {
        String body = bodyAsString(asViewer(get("/api/v1/incidents/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andReturn());

        assertThat(body).contains("\"traceId\"");
        Map<String, Object> parsed = json.readValue(body, Map.class);
        assertThat(parsed.get("traceId").toString()).isNotBlank();
    }

    @Test
    @DisplayName("an unauthenticated request is 401, not a redirect to a login page")
    void anonymousRequestsAreUnauthorized() throws Exception {
        // A REST API answering an anonymous call with 302 sends curl into a login form
        // and tells it nothing. The JWT flow has no session to redirect to.
        mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a garbage bearer token is 401")
    void invalidTokenIsUnauthorized() throws Exception {
        mvc.perform(get("/api/v1/incidents").header(HttpHeaders.AUTHORIZATION, "Bearer not.a.jwt"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    /** A well-formed event with one field replaced by a value the target type rejects. */
    private ResultActions invalidEvent(String field, String badValue) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sourceEventId", "diag-" + UUID.randomUUID());
        payload.put("sourceScope", "diagnostics");
        payload.put("service", "payment-service");
        payload.put("environment", "staging");
        payload.put("eventType", "LOG");
        payload.put("severity", "HIGH");
        payload.put("occurredAt", Instant.now().minusSeconds(5).toString());
        payload.put("message", "diagnostic probe");
        payload.put(field, badValue);

        return asEngineer(post("/api/v1/events").contentType(json()).content(toJson(payload)));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detailsOf(ResultActions call) throws Exception {
        Map<String, Object> body = body(call
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andReturn());
        return (Map<String, Object>) body.get("details");
    }

    private String message(Map<String, Object> details, String field) {
        Object value = details.get(field);
        assertThat(value).as("details[%s]", field).isInstanceOf(String.class);
        return (String) value;
    }
}
