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

    protected MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder builder) {
        return as(builder, adminToken());
    }

    protected MockHttpServletRequestBuilder asEngineer(MockHttpServletRequestBuilder builder) {
        return as(builder, engineerToken());
    }

    protected MockHttpServletRequestBuilder asViewer(MockHttpServletRequestBuilder builder) {
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

    protected String toJson(Object value) throws Exception {
        return json.writeValueAsString(value);
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