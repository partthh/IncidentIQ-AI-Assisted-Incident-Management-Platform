package com.sentinelai.realtime;

import java.util.UUID;

/** STOMP destination naming, in one place so publisher and authoriser cannot drift. */
public final class Destinations {

    public static final String INCIDENT_FEED = "/topic/incidents";
    public static final String INCIDENT_DETAIL_PREFIX = "/topic/incidents/";
    public static final String USER_NOTIFICATIONS = "/user/queue/notifications";
    public static final String COMMAND_PREFIX = "/app/incidents/";

    private Destinations() {
    }

    public static String incidentTopic(UUID incidentId) {
        return INCIDENT_DETAIL_PREFIX + incidentId;
    }

    /** Extracts the incident id from {@code /topic/incidents/{id}}, or null if absent. */
    public static String incidentIdFromTopic(String destination) {
        if (destination == null || !destination.startsWith(INCIDENT_DETAIL_PREFIX)) {
            return null;
        }
        String tail = destination.substring(INCIDENT_DETAIL_PREFIX.length());
        int slash = tail.indexOf('/');
        return slash < 0 ? tail : tail.substring(0, slash);
    }

    public static boolean isCommandDestination(String destination) {
        return destination != null && destination.startsWith(COMMAND_PREFIX);
    }
}
