package com.sentinelai.realtime;

import com.sentinelai.security.JwtService;
import com.sentinelai.security.SentinelPrincipal;
import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP {@code CONNECT} frame.
 *
 * <p>A browser cannot attach an {@code Authorization} header to a WebSocket upgrade, so
 * the handshake is anonymous and identity arrives in the first STOMP frame instead. Clients
 * send {@code Authorization: Bearer <jwt>} there, which keeps the token out of the URL
 * where it would end up in proxy access logs.
 *
 * <p>An unauthenticated CONNECT is refused outright rather than being allowed through
 * unauthenticated: once a session has a principal, destination authorisation has
 * something to check against.
 *
 * <h2>Why this must not use {@code StompHeaderAccessor.wrap}</h2>
 *
 * <p>The principal has to outlive the CONNECT frame, and the only thing that carries it
 * forward is Spring's {@code StompSubProtocolHandler.SessionInfo}. Spring stores the
 * session's principal there and re-applies it to every subsequent frame
 * ({@code accessor.setUser(getUser(session))}). The {@code SessionInfo} is updated by a
 * change callback that Spring attaches to <em>the accessor instance registered in the
 * message headers</em>:
 *
 * <pre>{@code
 * accessor.setUserChangeCallback(sessionInfo);   // attached to the registered accessor
 * channel.send(message);                          // interceptors run here
 * ...
 * sessionInfo.getUser();                          // every later frame re-reads this
 * }</pre>
 *
 * <p>{@code StompHeaderAccessor.wrap(message)} constructs a <strong>new</strong> accessor
 * from the message rather than returning the registered one. Calling {@code setUser} on
 * that copy writes the header into a private map and — because the copy has no change
 * callback — silently leaves {@code SessionInfo.user} null. CONNECT then succeeds, and the
 * very next SUBSCRIBE is rejected as "Unauthenticated STOMP frame", which looks exactly
 * like a broken token and is not one.
 *
 * <p>So the registered accessor is fetched with
 * {@link MessageHeaderAccessor#getAccessor(Message, Class)}, which is the same call Spring
 * itself makes on these messages, and the principal is set on it in place. The message is
 * returned unchanged: the accessor is mutated, not the message headers, and the accessor
 * may legitimately be immutable by this point (Spring calls {@code setImmutable()} unless
 * it detects an immutable-message interceptor on the channel), in which case rebuilding
 * from {@code getMessageHeaders()} would yield {@code null}.
 */
@Component
public class StompJwtAuthChannelInterceptor implements ChannelInterceptor {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public StompJwtAuthChannelInterceptor(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = accessorFor(message);
        if (accessor == null || !StompCommand.CONNECT.equals(accessor.getCommand())) {
            return message;
        }

        String header = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            throw new MessagingException("STOMP CONNECT requires an Authorization: Bearer <token> header");
        }
        SentinelPrincipal principal = jwtService.verify(header.substring(BEARER_PREFIX.length()).trim())
                .orElseThrow(() -> new MessagingException("Invalid or expired access token"));

        // Fires SessionInfo's change callback, which is what makes the principal visible
        // to the SUBSCRIBE and SEND frames that follow on this same socket.
        accessor.setUser(principal);
        return message;
    }

    /**
     * The accessor registered in the message headers, which is the instance carrying
     * Spring's user-change callback.
     *
     * <p>Falls back to {@link StompHeaderAccessor#wrap(Message)} only for messages that
     * carry no accessor at all — those cannot be a CONNECT frame Spring produced, but
     * wrapping keeps the refusal path working rather than silently skipping authentication.
     */
    private static StompHeaderAccessor accessorFor(Message<?> message) {
        StompHeaderAccessor registered = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        return registered != null ? registered : StompHeaderAccessor.wrap(message);
    }

    /** Exposed for tests and for the handshake-time hint. */
    public static List<String> requiredHeaders() {
        return List.of(AUTHORIZATION_HEADER);
    }
}