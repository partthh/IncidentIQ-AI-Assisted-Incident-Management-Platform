package com.sentinelai.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelai.security.AppUser;
import com.sentinelai.security.AppUserRepository;
import com.sentinelai.security.JwtService;
import com.sentinelai.security.SentinelPrincipal;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared HTTP test plumbing: tokens for the seeded roles and JSON helpers.
 *
 * <p>Tokens are issued by the real {@link JwtService} from real seeded users rather
 * than hand-built strings. A fabricated token would pass signature verification
 * only if the test duplicated the signing logic, which means the test stops covering
 * the thing that actually breaks in production: a token the server refuses.
 */
@AutoConfigureMockMvc
public abstract class ApiTestSupport extends PostgresIntegrationTest {

    protected static final String PASSWORD = "sentinel123";

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper json;

    @Autowired
    protected AppUserRepository users;

    @Autowired
    protected JwtService jwt;

    protected String tokenFor(String email) {
        AppUser user = users.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new IllegalStateException("Seeded user missing: " + email));
        return jwt.issue(user).token();
    }

    protected String adminToken() {
        return tokenFor("admin@sentinel.dev");
    }

    protected String engineerToken() {
        return tokenFor("priya@sentinel.dev");
    }

    protected String viewerToken() {
        return tokenFor("viewer@sentinel.dev");
    }

    /**
     * Adds a bearer token and performs the request.
     *
     * <p>Returns {@link ResultActions} rather than the builder so tests read as one
     * fluent chain from request to assertion, and so no test can accidentally
     * forget the {@code perform} call.
     */
    protected ResultActions as(MockHttpServletRequestBuilder builder, String token) throws Exception {
        return mvc.perform(builder.header("Authorization", "Bearer " + token));
    }

    protected ResultActions asAdmin(MockHttpServletRequestBuilder builder) throws Exception {
        return as(builder, adminToken());
    }

    protected ResultActions asEngineer(MockHttpServletRequestBuilder builder) throws Exception {
        return as(builder, engineerToken());
    }

    protected ResultActions asViewer(MockHttpServletRequestBuilder builder) throws Exception {
        return as(builder, viewerToken());
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> body(MvcResult result) throws Exception {
        String raw = result.getResponse().getContentAsString();
        return json.readValue(raw, Map.class);
    }

    protected String bodyAsString(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString();
    }

    /** Parses a response whose body is a JSON array rather than an object. */
    protected <T> T read(MvcResult result, Class<T> type) {
        try {
            return json.readValue(bodyAsString(result), type);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not parse test response", ex);
        }
    }

    protected String toJson(Object value) throws Exception {
        return json.writeValueAsString(value);
    }

    /**
     * Serialises an inline request body.
     *
     * <p>MockMvc's {@code content(String)} will not accept a {@code Map}, so every
     * inline body has to go through here. Having one name for it also means a
     * change to how bodies are written is a one-line change.
     */
    protected String j(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialise test body", ex);
        }
    }

    protected MediaType json() {
        return MediaType.APPLICATION_JSON;
    }

    /** The identity the security layer will build for a token issued to this user. */
    protected SentinelPrincipal principal(String email) {
        AppUser user = users.findByEmailIgnoreCase(email).orElseThrow();
        return new SentinelPrincipal(user.getId(), user.getEmail(), user.getName(), user.getRole());
    }
}