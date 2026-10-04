package com.sentinelai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinelai.security.SentinelPrincipal;
import com.sentinelai.support.ApiTestSupport;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

/**
 * The WebSocket's authentication boundary, tested at the frame level.
 *
 * <p>MockMvc cannot exercise this: a STOMP {@code CONNECT} is not an HTTP request, so
 * the REST security tests say nothing about whether the socket is actually
 * authenticated. Since the socket is how a client learns about incidents, a socket that
 * accepts anonymous callers is an unauthenticated incident feed — which is exactly the
 * failure these tests exist to rule out.
 *
 * <p>The interceptor is also asserted directly for the refusal paths, because a socket
 * test can only observe that the handshake failed, not <em>why</em>.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class StompAuthorizationTest extends ApiTestSupport {

    @LocalServerPort
    private int port;

    private WebSocketStompClient stompClient;

    @Autowired
    private SimpMessagingTemplate broker;

    @BeforeEach
    void setUpClient() {
        stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        // start() is still required: it brings up the client's message-processing executor.
        // It does not supply a TaskScheduler outside a Spring context, which is why the
        // subscription is verified by delivering a message rather than by waiting for a
        // RECEIPT — receipt tracking needs that scheduler, and waiting on one would test
        // the client's plumbing rather than the server's authorisation boundary.
        stompClient.start();
    }

    @AfterEach
    void shutDownClient() {
        if (stompClient != null) {
            stompClient.stop();
        }
    }

    // ------------------------------------------------------------ interceptor

    @Test
    @DisplayName("CONNECT without a token is refused rather than allowed in unauthenticated")
    void connectWithoutTokenIsRefused() {
        StompJwtAuthChannelInterceptor interceptor = new StompJwtAuthChannelInterceptor(jwt);

        assertThatThrownBy(() -> interceptor.preSend(connectFrame(null), channel()))
                .hasMessageContaining("Authorization");
    }

    @Test
    @DisplayName("CONNECT with a malformed Authorization header is refused")
    void connectWithNonBearerHeaderIsRefused() {
        StompJwtAuthChannelInterceptor interceptor = new StompJwtAuthChannelInterceptor(jwt);

        // "Basic ..." and a bare "Bearer" are both plausible client bugs, and neither
        // should reach the token verifier.
        for (String header : List.of("Basic dXNlcjpwYXNz", "Bearer", "token", "")) {
            assertThatThrownBy(() -> interceptor.preSend(connectFrame(header), channel()))
                    .as("header %s", header)
                    .hasMessageContaining("Authorization");
        }

        // "Bearer " followed by nothing still satisfies the prefix check, so it is the
        // verifier that refuses it. Asserted separately because lumping it in with the
        // cases above would be asserting a message the code never produces.
        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer    "), channel()))
                .as("a Bearer prefix with an empty token is a token failure, not a header failure")
                .hasMessageContaining("Invalid or expired access token");
    }

    @Test
    @DisplayName("CONNECT with an expired or forged token is refused")
    void connectWithBadTokenIsRefused() {
        StompJwtAuthChannelInterceptor interceptor = new StompJwtAuthChannelInterceptor(jwt);

        assertThatThrownBy(() -> interceptor.preSend(connectFrame("Bearer not.a.real.jwt"), channel()))
                .hasMessageContaining("Invalid or expired");
    }

    @Test
    @DisplayName("a valid CONNECT attaches the principal that destination checks run against")
    void connectWithValidTokenAttachesPrincipal() {
        StompJwtAuthChannelInterceptor interceptor = new StompJwtAuthChannelInterceptor(jwt);

        Message<?> message = interceptor.preSend(connectFrame("Bearer " + viewerToken()), channel());
        java.security.Principal user = StompHeaderAccessor.wrap(message).getUser();
        assertThat(user).isInstanceOf(SentinelPrincipal.class);
        SentinelPrincipal principal = (SentinelPrincipal) user;

        assertThat(principal).isNotNull();
        assertThat(principal.email()).isEqualTo("viewer@sentinel.dev");
        assertThat(principal.role().name()).isEqualTo("VIEWER");
    }

    /**
     * The principal has to be set on the accessor <em>registered in the message headers</em>.
     *
     * <p>That instance is the only one carrying Spring's user-change callback, and the
     * callback is the sole path by which the principal survives past the CONNECT frame —
     * {@code StompSubProtocolHandler.SessionInfo} is what every later SUBSCRIBE reads. An
     * interceptor that sets the user on a {@code StompHeaderAccessor.wrap} copy looks
     * correct in isolation: the header is set, {@code wrap(message).getUser()} returns it,
     * and the socket still refuses every subscription as unauthenticated.
     *
     * <p>This asserts the registered accessor specifically, so that regression cannot come
     * back quietly.
     */
    @Test
    @DisplayName("the principal lands on the registered accessor, not only on a wrapped copy")
    void connectSetsThePrincipalOnTheRegisteredAccessor() {
        StompJwtAuthChannelInterceptor interceptor = new StompJwtAuthChannelInterceptor(jwt);

        Message<?> message = interceptor.preSend(connectFrame("Bearer " + viewerToken()), channel());

        StompHeaderAccessor registered = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        assertThat(registered)
                .as("the frame must carry a discoverable accessor, as Spring's own frames do")
                .isNotNull();
        assertThat(registered.getUser())
                .as("Spring re-reads the principal from this instance for every later frame")
                .isInstanceOf(SentinelPrincipal.class)
                .extracting(user -> ((SentinelPrincipal) user).email())
                .isEqualTo("viewer@sentinel.dev");
    }

    // ---------------------------------------------------------------- live socket

    @Test
    @DisplayName("a real CONNECT carrying no token never reaches CONNECTED")
    void liveSocketRefusesAnonymousConnect() throws Exception {
        assertServerIsListening();

        CountDownLatch connected = new CountDownLatch(1);

        // The overload that can actually carry CONNECT headers is used deliberately, with
        // none supplied. An earlier version of this test called the three-argument
        // connectAsync(url, handler, Object...), which silently treats any extra argument
        // as a URI template variable — so it passed for the right-looking reason (refused)
        // while proving nothing: the token had been discarded, not omitted.
        CompletableFuture<StompSession> attempt = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                new StompHeaders(),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders headers) {
                        connected.countDown();
                    }
                });

        // The server answers a bad CONNECT by closing the socket, so the failure arrives as
        // a completed-exceptionally future rather than a thrown MessagingException.
        // Asserting on the latch alone would pass for the wrong reason — a client that
        // never started also never connects.
        assertThatThrownBy(() -> attempt.get(10, TimeUnit.SECONDS))
                .as("an anonymous CONNECT must not succeed")
                .isNotNull();
        assertThat(connected.await(2, TimeUnit.SECONDS))
                .as("the socket must never report a connected session without a token")
                .isFalse();
    }

    /**
     * Confirms a real server is on {@link #port} before the socket is blamed for
     * anything.
     *
     * <p>A mock web environment would leave {@code port} at 0 and every socket
     * failure would look identical to a correctly-refused anonymous connection. This
     * assertion makes the two failures distinguishable in the report rather than
     * leaving the next person to guess.
     */
    private void assertServerIsListening() throws Exception {
        assertThat(port).as("the test must run against a real port").isGreaterThan(0);

        HttpClient probe = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        HttpResponse<String> response = probe.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode())
                .as("the embedded server must be reachable for a socket result to mean anything")
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a real CONNECT carrying a valid token is accepted and can subscribe")
    void liveSocketAcceptsAuthenticatedConnect() throws Exception {
        assertServerIsListening();

        CountDownLatch connected = new CountDownLatch(1);
        List<String> errors = new CopyOnWriteArrayList<>();

        // connectAsync(url, wsHandshakeHeaders, connectHeaders, handler, uriVars) — the
        // overload whose third argument really is the CONNECT frame's headers. The
        // three-argument overload silently absorbs a StompHeaders argument as a URI
        // template variable, which drops the token without any error.
        StompSession session = stompClient.connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders("Bearer " + engineerToken()),
                new StompSessionHandlerAdapter() {
                    @Override
                    public void afterConnected(StompSession session, StompHeaders headers) {
                        connected.countDown();
                    }

                    @Override
                    public void handleException(StompSession session, StompCommand command,
                                                StompHeaders headers, byte[] payload, Throwable exception) {
                        errors.add(command + ": " + exception.getMessage());
                    }
                }).get(5, TimeUnit.SECONDS);

        try {
            assertThat(connected.await(5, TimeUnit.SECONDS)).isTrue();

            CountDownLatch delivered = new CountDownLatch(1);
            List<String> received = new CopyOnWriteArrayList<>();

            StompSession.Subscription subscription = session.subscribe("/topic/incidents",
                    new StompFrameHandler() {
                        @Override
                        public Type getPayloadType(StompHeaders headers) {
                            return byte[].class;
                        }

                        @Override
                        public void handleFrame(StompHeaders headers, Object payload) {
                            received.add(new String((byte[]) payload, java.nio.charset.StandardCharsets.UTF_8));
                            delivered.countDown();
                        }
                    });

            // Delivery is the assertion, not "subscribe did not throw". A server that
            // accepts the frame and silently drops every message would satisfy the weaker
            // check, and so would a client whose SUBSCRIBE was rejected for a reason that
            // never surfaced. Publishing and waiting for the bytes to come back closes both
            // holes — and it is exactly the step that was broken: an authenticated CONNECT
            // succeeded while every subsequent SUBSCRIBE was refused as unauthenticated.
            //
            // Published repeatedly until one lands. subscribe() returns once the frame is
            // written, not once the server has registered it, so a single publish races the
            // registration and the test fails intermittently for a reason that has nothing
            // to do with authorisation.
            String eventId = "evt-authorization-test";
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!delivered.await(250, TimeUnit.MILLISECONDS) && System.nanoTime() < deadline) {
                broker.convertAndSend("/topic/incidents",
                        new RealtimeEnvelope(eventId, RealtimeEventType.INCIDENT_UPDATED,
                                UUID.randomUUID(), "INC-AUTHZ", Instant.now(), 1L, Map.of("probe", true)));
            }

            assertThat(delivered.await(1, TimeUnit.SECONDS))
                    .as("an authenticated client should receive what is published to the feed topic")
                    .isTrue();
            // More than one may have landed, since publishing retries — so assert on all of
            // them rather than on an exact count, which the retry loop makes meaningless.
            assertThat(received).isNotEmpty();
            assertThat(received).allSatisfy(frame -> assertThat(frame)
                    .contains(eventId)
                    .contains("INC-AUTHZ"));
            assertThat(subscription.getSubscriptionId()).isNotBlank();
            assertThat(errors).isEmpty();
        } finally {
            session.disconnect();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** The single native header a client puts on its CONNECT frame. */
    private static StompHeaders connectHeaders(String authorization) {
        StompHeaders headers = new StompHeaders();
        headers.set("Authorization", authorization);
        return headers;
    }

    private Message<byte[]> connectFrame(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.setNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * A channel that accepts everything.
     *
     * <p>The interceptor reads only the message, but {@link MessageChannel} declares
     * two abstract {@code send} overloads, so both must exist. A stub that quietly
     * accepted one signature would not compile — which is the right outcome, because a
     * wrong-looking signature here would mean the test is exercising a different
     * interface than production passes.
     */
    private MessageChannel channel() {
        return new MessageChannel() {
            @Override
            public boolean send(Message<?> message) {
                return true;
            }

            @Override
            public boolean send(Message<?> message, long timeout) {
                return true;
            }
        };
    }
}
