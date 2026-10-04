package com.sentinelai.realtime;

import com.sentinelai.security.JwtService;
import com.sentinelai.security.SentinelPrincipal;
import java.util.List;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

/**
 * Authenticates the STOMP {@code CONNECT} frame.
 *
 * <p>A browser cannot attach an {@code Authorization} header to a WebSocket
 * upgrade, so the handshake is anonymous and identity arrives in the first STOMP
 * frame instead. Clients send {@code Authorization: Bearer <jwt>} there, which
 * keeps the token out of the URL where it would end up in proxy access logs.
 *
 * <p>An unauthenticated CONNECT is refused outright rather than being allowed
 * through unauthenticated: once a session has a principal, destination
 * authorisation has something to check against.
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
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        if (StompCommand.CONNECT.equals(accessor.getCommand())) {
            String header = accessor.getFirstNativeHeader(AUTHORIZATION_HEADER);
            if (header == null || !header.startsWith(BEARER_PREFIX)) {
                throw new org.springframework.messaging.MessagingException(
                        "STOMP CONNECT requires an Authorization: Bearer <token> header");
            }
            SentinelPrincipal principal = jwtService.verify(header.substring(BEARER_PREFIX.length()).trim())
                    .orElseThrow(() -> new org.springframework.messaging.MessagingException(
                            "Invalid or expired access token"));
            accessor.setUser(principal);
            // Spring derives the per-user reply destination from this.
            accessor.setLeaveMutable(true);
        }
        return org.springframework.messaging.support.MessageBuilder.createMessage(
                message.getPayload(), accessor.getMessageHeaders());
    }

    /** Exposed for tests and for the handshake-time hint. */
    public static List<String> requiredHeaders() {
        return List.of(AUTHORIZATION_HEADER);
    }
}
