package com.sentinelai.incidents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sentinelai.common.InvalidStateTransitionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Every write to an incident passes through this, so the tests below are the
 * executable form of the lifecycle policy. The property worth protecting above all
 * is that {@code RESOLVED} is terminal: an incident that could be un-resolved would
 * make "resolved" meaningless as an audit fact and would corrupt MTTR.
 */
class IncidentStateMachineTest {

    @ParameterizedTest
    @CsvSource({
            "OPEN,ACKNOWLEDGED",
            "OPEN,INVESTIGATING",
            "OPEN,RESOLVED",
            "ACKNOWLEDGED,INVESTIGATING",
            "ACKNOWLEDGED,RESOLVED",
            "INVESTIGATING,RESOLVED"
    })
    void legalTransitionsAreAllowed(IncidentStatus from, IncidentStatus to) {
        assertThat(IncidentStateMachine.canTransition(from, to)).isTrue();
        assertThatCode(() -> IncidentStateMachine.require(from, to)).doesNotThrowAnyException();
    }

    @Test
    void resolvedIsTerminal() {
        assertThat(IncidentStateMachine.legalTargets(IncidentStatus.RESOLVED)).isEmpty();

        for (IncidentStatus target : IncidentStatus.values()) {
            assertThat(IncidentStateMachine.canTransition(IncidentStatus.RESOLVED, target))
                    .as("RESOLVED -> %s must be rejected", target)
                    .isFalse();
        }
    }

    @Test
    void cannotSkipBackwardsInTheLifecycle() {
        // Not the same as re-acknowledging while investigating, which is a tolerated
        // no-op. Going from ACKNOWLEDGED back to OPEN is a genuine error.
        assertThatThrownBy(() -> IncidentStateMachine.require(IncidentStatus.ACKNOWLEDGED,
                IncidentStatus.OPEN))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThatThrownBy(() -> IncidentStateMachine.require(IncidentStatus.INVESTIGATING,
                IncidentStatus.OPEN))
                .isInstanceOf(InvalidStateTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(IncidentStatus.class)
    void sameStatusIsNeverAnIllegalTransition(IncidentStatus status) {
        // Every state tolerates re-asserting itself: an HTTP client retrying after a
        // timeout must not be told it committed something illegal.
        assertThatCode(() -> IncidentStateMachine.require(status, status))
                .doesNotThrowAnyException();
    }

    @Test
    void acknowledgingSomethingAlreadyInvestigatingIsANoOpNotAFailure() {
        // The acknowledgement request raced with an investigation that started first.
        // Rejecting it would fail a legitimate call and force every client to
        // re-read state before acting.
        assertThat(IncidentStateMachine.isIdempotentNoOp(IncidentStatus.INVESTIGATING,
                IncidentStatus.ACKNOWLEDGED)).isTrue();
        assertThatCode(() -> IncidentStateMachine.require(IncidentStatus.INVESTIGATING,
                IncidentStatus.ACKNOWLEDGED)).doesNotThrowAnyException();
    }

    @Test
    void reInvestigatingIsANoOp() {
        assertThat(IncidentStateMachine.isIdempotentNoOp(IncidentStatus.INVESTIGATING,
                IncidentStatus.INVESTIGATING)).isTrue();
    }

    @Test
    void idempotentNoOpsDoNotCoverReversingDirection() {
        // Being lenient about one retry must not become general leniency.
        assertThat(IncidentStateMachine.isIdempotentNoOp(IncidentStatus.ACKNOWLEDGED,
                IncidentStatus.OPEN)).isFalse();
        assertThat(IncidentStateMachine.isIdempotentNoOp(IncidentStatus.ACKNOWLEDGED,
                IncidentStatus.INVESTIGATING)).isTrue();
        assertThatThrownBy(() -> IncidentStateMachine.require(IncidentStatus.ACKNOWLEDGED,
                IncidentStatus.OPEN)).isInstanceOf(InvalidStateTransitionException.class);
    }

    @Test
    void transitionErrorNamesTheLegalOptions() {
        // The message is the only thing an API consumer sees; "illegal transition"
        // with no guidance forces them to read the source to recover.
        assertThatThrownBy(() -> IncidentStateMachine.require(IncidentStatus.ACKNOWLEDGED,
                IncidentStatus.OPEN))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("ACKNOWLEDGED")
                .hasMessageContaining("OPEN")
                .hasMessageContaining("RESOLVED");
    }

    @Test
    void terminalStateErrorSaysSo() {
        assertThatThrownBy(() -> IncidentStateMachine.require(IncidentStatus.RESOLVED,
                IncidentStatus.ACKNOWLEDGED))
                .isInstanceOf(InvalidStateTransitionException.class)
                .hasMessageContaining("terminal");
    }
}