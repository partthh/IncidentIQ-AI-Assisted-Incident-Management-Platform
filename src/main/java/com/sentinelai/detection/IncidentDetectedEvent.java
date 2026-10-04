package com.sentinelai.detection;

import java.util.UUID;

/**
 * Announces that detection changed an incident.
 *
 * <p>Published from inside the detection transaction and consumed with
 * {@code AFTER_COMMIT}, which buys two things at once: the AI module is not a
 * dependency of the detection engine (it is one optional subscriber among any
 * number of future ones), and no subscriber can run against incident state that a
 * rollback then erases.
 *
 * <p>Deliberately carries the incident id rather than the entity. Publishing an
 * entity would hand subscribers a managed instance whose state may not be what the
 * committed rows say, and would tempt them to mutate it.
 */
public record IncidentDetectedEvent(UUID incidentId, String incidentReference, String action) {

    public static final String SOURCE_AUTO_INVESTIGATE = "AUTO_INVESTIGATE";
}