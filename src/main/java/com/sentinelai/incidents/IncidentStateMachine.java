package com.sentinelai.incidents;

import com.sentinelai.common.InvalidStateTransitionException;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The incident lifecycle, as pure data with no persistence or Spring coupling.
 *
 * <p>Keeping it separate means the rules are exhaustively unit-testable and
 * reviewable on their own, and that no service can quietly invent a legal
 * transition. Anything not listed here is rejected — including reopening a
 * resolved incident, which requires a new fingerprint occurrence instead.
 */
public final class IncidentStateMachine {

    private static final Map<IncidentStatus, Set<IncidentStatus>> TRANSITIONS = Map.of(
            IncidentStatus.OPEN, EnumSet.of(IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING,
                    IncidentStatus.RESOLVED),
            IncidentStatus.ACKNOWLEDGED, EnumSet.of(IncidentStatus.INVESTIGATING, IncidentStatus.RESOLVED),
            IncidentStatus.INVESTIGATING, EnumSet.of(IncidentStatus.RESOLVED),
            IncidentStatus.RESOLVED, EnumSet.noneOf(IncidentStatus.class));

    /**
     * Transitions accepted even though they are not state changes.
     *
     * <p>Two categories, both retry-safety. Re-asserting the current status is a
     * no-op that any idempotent client may legitimately repeat after a timeout, and
     * acknowledging something already under investigation means a second engineer
     * clicked the button after a page reload — failing that would push
     * duplicate-handling onto every caller for no benefit. Everything else must be an
     * explicit transition.
     */
    private static final Map<IncidentStatus, Set<IncidentStatus>> IDEMPOTENT = Map.of(
            IncidentStatus.OPEN, EnumSet.of(IncidentStatus.OPEN),
            IncidentStatus.ACKNOWLEDGED, EnumSet.of(IncidentStatus.ACKNOWLEDGED, IncidentStatus.INVESTIGATING),
            IncidentStatus.INVESTIGATING, EnumSet.of(IncidentStatus.INVESTIGATING,
                    IncidentStatus.ACKNOWLEDGED),
            IncidentStatus.RESOLVED, EnumSet.of(IncidentStatus.RESOLVED));

    private IncidentStateMachine() {
    }

    public static Set<IncidentStatus> legalTargets(IncidentStatus from) {
        return TRANSITIONS.getOrDefault(from, Set.of());
    }

    public static boolean canTransition(IncidentStatus from, IncidentStatus to) {
        return legalTargets(from).contains(to);
    }

    /** @return true when the request should be treated as a successful no-op */
    public static boolean isIdempotentNoOp(IncidentStatus from, IncidentStatus to) {
        return IDEMPOTENT.getOrDefault(from, Set.of()).contains(to);
    }

    public static void require(IncidentStatus from, IncidentStatus to) {
        if (canTransition(from, to) || isIdempotentNoOp(from, to)) {
            return;
        }
        throw new InvalidStateTransitionException(from.name(), to.name(), describe(from));
    }

    private static String describe(IncidentStatus from) {
        Set<IncidentStatus> targets = legalTargets(from);
        return targets.isEmpty() ? "(terminal state, no further transitions)" : targets.toString();
    }
}
