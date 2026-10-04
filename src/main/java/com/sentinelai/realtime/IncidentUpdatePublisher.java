package com.sentinelai.realtime;

import com.sentinelai.config.RealtimeProperties;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Broadcasts committed incident changes.
 *
 * <p>Every method here must be called through {@link AfterCommitExecutor} by the
 * caller (or from a context with no active transaction) so that clients can never
 * be told about state that was rolled back.
 */
@Component
public class IncidentUpdatePublisher {

    private static final Logger log = LoggerFactory.getLogger(IncidentUpdatePublisher.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final RealtimeProperties properties;

    public IncidentUpdatePublisher(SimpMessagingTemplate messagingTemplate, RealtimeProperties properties) {
        this.messagingTemplate = messagingTemplate;
        this.properties = properties;
    }

    public void publishFeed(RealtimeEventType type, UUID incidentId, String reference, long sequence,
                            Object payload) {
        publish(Destinations.INCIDENT_FEED, type, incidentId, reference, sequence, payload);
    }

    public void publishIncident(RealtimeEventType type, UUID incidentId, String reference, long sequence,
                                Object payload) {
        publish(Destinations.incidentTopic(incidentId), type, incidentId, reference, sequence, payload);
    }

    /** Personal notification, delivered only to the addressed user's own queue. */
    public void notifyUser(UUID userId, RealtimeEventType type, UUID incidentId, String reference,
                           long sequence, Object payload) {
        if (!properties.enabled()) {
            return;
        }
        RealtimeEnvelope envelope = RealtimeEnvelope.of(type, incidentId, reference, sequence, payload);
        try {
            messagingTemplate.convertAndSendToUser(userId.toString(), Destinations.USER_NOTIFICATIONS, envelope);
        } catch (RuntimeException ex) {
            // A failed notification must never fail the business operation that
            // triggered it; the state is already committed and the UI can recover
            // from the REST snapshot.
            log.warn("Failed to notify user {} about incident {}", userId, reference, ex);
        }
    }

    private void publish(String destination, RealtimeEventType type, UUID incidentId, String reference,
                         long sequence, Object payload) {
        if (!properties.enabled()) {
            return;
        }
        RealtimeEnvelope envelope = RealtimeEnvelope.of(type, incidentId, reference, sequence, payload);
        try {
            messagingTemplate.convertAndSend(destination, envelope);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish {} to {}", type, destination, ex);
        }
    }
}
