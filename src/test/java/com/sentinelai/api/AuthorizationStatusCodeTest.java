package com.sentinelai.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.sentinelai.support.ApiTestSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Status codes as a real HTTP client sees them.
 *
 * <p>{@link ApiDiagnosticsTest} covers what the API says when it cannot help, but it uses
 * MockMvc — and MockMvc does not reproduce a servlet-container error re-dispatch. That
 * omission hid a real defect: Spring Security's default {@code AccessDeniedHandlerImpl}
 * answers a forbidden request with {@code sendError(403)}, which the container turns into
 * an ERROR dispatch to {@code /error}. The security chain runs again on that dispatch with
 * no principal, so the authentication entry point answered instead.
 *
 * <p>A viewer attempting a write was therefore told {@code 401 UNAUTHENTICATED —
 * "Authentication required"} — a request to go and log in again, for a caller who was
 * perfectly well authenticated. Every assertion in this class was green under MockMvc while
 * the live API returned 401, which is the whole reason it exists.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AuthorizationStatusCodeTest extends ApiTestSupport {

    @LocalServerPort
    private int port;

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @Test
    @DisplayName("a viewer attempting a write gets 403, not 401")
    void viewerWriteIsForbidden() throws Exception {
        // Deliberately a write the viewer is authenticated for but not permitted to make.
        // The distinction under test: 401 means "we do not know who you are", and this
        // caller is known.
        HttpResponse<String> response = send("POST", "/api/v1/events", viewerToken(),
                j(Map.of(
                        "sourceEventId", "authz-" + UUID.randomUUID(),
                        "sourceScope", "authorization-test",
                        "service", "payment-service",
                        "environment", "staging",
                        "eventType", "LATENCY_SPIKE",
                        "severity", "HIGH",
                        "message", "viewer must not be allowed to write this",
                        "occurredAt", java.time.Instant.now().toString(),
                        "metadata", Map.of("p95LatencyMs", 2500))));

        assertThat(response.statusCode())
                .as("a known caller with the wrong role is forbidden, not unauthenticated")
                .isEqualTo(403);
        assertThat(response.body()).contains("\"code\":\"FORBIDDEN\"");
    }

    @Test
    @DisplayName("the 403 uses the same envelope as every other failure")
    void forbiddenBodyMatchesTheEnvelope() throws Exception {
        // Not cosmetic: a client that switches on `code` has nothing to switch on if this
        // response falls back to Spring Boot's default error body.
        HttpResponse<String> response = send("POST", "/api/v1/events", viewerToken(),
                j(Map.of("sourceEventId", "authz-envelope-" + UUID.randomUUID())));

        Map<String, Object> parsed = json.readValue(response.body(), Map.class);
        assertThat(parsed).containsKeys("status", "code", "message", "path");
        assertThat(parsed.get("status")).isEqualTo(403);
        assertThat(parsed.get("code")).isEqualTo("FORBIDDEN");
        assertThat((String) parsed.get("path")).startsWith("/api/v1/events");
    }

    @Test
    @DisplayName("an anonymous caller still gets 401 — the 403 fix did not swallow it")
    void anonymousIsStillUnauthorized() throws Exception {
        HttpResponse<String> response = send("GET", "/api/v1/incidents", null, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("UNAUTHENTICATED");
    }

    @Test
    @DisplayName("an engineer may write, so the 403 is a role decision and not a blanket refusal")
    void engineerWriteIsAllowed() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/events", engineerToken(),
                j(Map.of(
                        "sourceEventId", "authz-engineer-" + UUID.randomUUID(),
                        "sourceScope", "authorization-test",
                        "service", "payment-service",
                        "environment", "staging",
                        "eventType", "LATENCY_SPIKE",
                        "severity", "HIGH",
                        "message", "engineer writes are permitted",
                        "occurredAt", java.time.Instant.now().toString(),
                        "metadata", Map.of("p95LatencyMs", 2600))));

        assertThat(response.statusCode()).isIn(200, 202);
    }

    @Test
    @DisplayName("a wrong password says the credentials were invalid, not that auth is required")
    void badPasswordIsReportedAsInvalidCredentials() throws Exception {
        // "Authentication required" tells someone who just submitted a wrong password to
        // go and find a missing token. They will look for a header that was never the
        // problem.
        HttpResponse<String> response = send("POST", "/api/v1/auth/login", null,
                j(Map.of("email", "admin@sentinel.dev", "password", "definitely-not-it")));

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body())
                .contains("INVALID_CREDENTIALS")
                .doesNotContain("Authentication required");
    }

    @Test
    @DisplayName("an unknown user and a wrong password are indistinguishable")
    void loginFailuresAreIndistinguishable() throws Exception {
        // The pair together is the point: if either answer differs, this endpoint becomes a
        // user-enumeration oracle.
        HttpResponse<String> wrongPassword = send("POST", "/api/v1/auth/login", null,
                j(Map.of("email", "admin@sentinel.dev", "password", "definitely-not-it")));
        HttpResponse<String> unknownUser = send("POST", "/api/v1/auth/login", null,
                j(Map.of("email", "nobody@sentinel.dev", "password", "definitely-not-it")));

        assertThat(unknownUser.statusCode()).isEqualTo(wrongPassword.statusCode());
        assertThat(unknownUser.body()).isEqualTo(wrongPassword.body());
    }

    private HttpResponse<String> send(String method, String path, String token, String payload)
            throws Exception {
        HttpRequest.BodyPublisher body = payload == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload);

        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .method(method, body);
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }

        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://localhost:" + port;
    }
}