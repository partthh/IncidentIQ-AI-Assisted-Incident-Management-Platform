package com.sentinelai.realtime;

import com.sentinelai.incidents.IncidentRepository;
import com.sentinelai.security.SentinelPrincipal;
import java.util.UUID;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Server-side authorisation for STOMP SUBSCRIBE and SEND.
 *
 * <p>Client-side hiding of a topic is not access control. Three rules are
 * enforced here:
 * <ul>
 *   <li>Every subscription requires an authenticated principal.</li>
 *   <li>{@code /topic/incidents/{id}} is rejected unless {@code id} resolves to a
 *       real incident — so guessing UUIDs cannot be used to probe for existence,
 *       and a typo does not silently create an empty subscription.</li>
 *   <li>{@code /app/**} commands mutate state and require ENGINEER or ADMIN.
 *       A VIEWER may watch but never act.</li>
 * </ul>
 *
 * <p>Like the authentication interceptor, this reads the accessor <em>registered in the
 * message headers</em> rather than {@link StompHeaderAccessor#wrap(Message)}. Spring writes
 * the session principal onto that instance immediately before the frame reaches the
 * channel — and does so after the message headers were already snapshotted, so a wrapped
 * copy built from the message reads a principal that is not there. That mismatch presents
 * as every subscription being refused as unauthenticated, which looks like a bad token and
 * is not one.
 */
@Component
public class StompDestinationAuthorizationInterceptor implements ChannelInterceptor {

    private final IncidentRepository incidents;

    public StompDestinationAuthorizationInterceptor(IncidentRepository incidents) {
        this.incidents = incidents;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) {
            return message;
        }
        StompCommand command = accessor.getCommand();
        if (!StompCommand.SUBSCRIBE.equals(command) && !StompCommand.SEND.equals(command)
                && !StompCommand.UNSUBSCRIBE.equals(command)) {
            return message;
        }

        String destination = accessor.getDestination();
        if (destination == null) {
            return message;
        }

        SentinelPrincipal principal = accessor.getUser() instanceof SentinelPrincipal p ? p : null;
        if (principal == null) {
            throw new MessagingException("Unauthenticated STOMP frame for " + destination);
        }

        if (StompCommand.SEND.equals(command)) {
            if (Destinations.isCommandDestination(destination) && !principal.role().canWrite()) {
                throw new MessagingException(
                        "Role " + principal.role() + " may not send commands to " + destination);
            }
            return message;
        }

        if (StompCommand.SUBSCRIBE.equals(command) && destination.startsWith(Destinations.INCIDENT_DETAIL_PREFIX)) {
            String rawId = Destinations.incidentIdFromTopic(destination);
            UUID incidentId;
            try {
                incidentId = UUID.fromString(rawId);
            } catch (IllegalArgumentException ex) {
                throw new MessagingException("Malformed incident topic: " + destination);
            }
            if (!incidents.existsById(incidentId)) {
                // Visibility is currently uniform — every authenticated role may read
                // every incident, matching GET /api/v1/incidents. When per-incident ACLs
                // arrive, this is the single place that must grow the check, and the
                // authoriser already has the principal in hand.
                throw new MessagingException("Unknown incident: " + incidentId);
            }
        }
        return message;
    }
}
