package com.netpulse.alert;

import com.netpulse.alert.AlertDecider.Action;
import com.netpulse.alert.AlertDecider.Signal;
import com.netpulse.alert.AlertTypes.State;
import com.netpulse.common.error.ApiException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AlertDeciderTest {
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static Action decide(Signal s, boolean open, Instant pending, int duration, Instant now, boolean muted) {
        return AlertDecider.decide(s, open, pending, duration, now, muted);
    }

    @Test void breachWithNoDurationTriggersImmediately() {
        assertThat(decide(Signal.BREACH, false, null, 0, T0, false)).isEqualTo(Action.TRIGGER);
    }

    @Test void breachWithDurationStartsTheClockFirst() {
        assertThat(decide(Signal.BREACH, false, null, 300, T0, false)).isEqualTo(Action.START_PENDING);
    }

    @Test void waitsUntilTheDurationHasPassed() {
        assertThat(decide(Signal.BREACH, false, T0, 300, T0.plusSeconds(299), false)).isEqualTo(Action.NONE);
        assertThat(decide(Signal.BREACH, false, T0, 300, T0.plusSeconds(300), false)).isEqualTo(Action.TRIGGER);
    }

    @Test void aSustainedBreachDoesNotReTrigger() {
        assertThat(decide(Signal.BREACH, true, null, 0, T0, false)).isEqualTo(Action.NONE);
    }

    @Test void clearingBeforeTheDurationCancelsWithoutAnAlert() {
        assertThat(decide(Signal.CLEAR, false, T0, 300, T0.plusSeconds(10), false)).isEqualTo(Action.CLEAR_PENDING);
    }

    @Test void clearingResolvesAnOpenAlert() {
        assertThat(decide(Signal.CLEAR, true, null, 0, T0, false)).isEqualTo(Action.RESOLVE);
    }

    @Test void clearWithNothingOpenIsANoOp() {
        assertThat(decide(Signal.CLEAR, false, null, 0, T0, false)).isEqualTo(Action.NONE);
    }

    @Test void noDataNeverResolvesOrTriggers() {
        assertThat(decide(Signal.NO_DATA, true, null, 0, T0, false)).isEqualTo(Action.NONE);
        assertThat(decide(Signal.NO_DATA, false, T0, 0, T0, false)).isEqualTo(Action.NONE);
    }

    @Test void maintenanceMutesNewAlertsAndRestartsTheClock() {
        assertThat(decide(Signal.BREACH, false, null, 0, T0, true)).isEqualTo(Action.NONE);
        assertThat(decide(Signal.BREACH, false, T0, 60, T0.plusSeconds(120), true)).isEqualTo(Action.CLEAR_PENDING);
    }

    @Test void onlyTriggeredAlertsCanBeAcknowledged() {
        assertThatCode(() -> AlertDecider.requireAcknowledgeable(State.TRIGGERED)).doesNotThrowAnyException();
        assertThatThrownBy(() -> AlertDecider.requireAcknowledgeable(State.ACKNOWLEDGED)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> AlertDecider.requireAcknowledgeable(State.RESOLVED)).isInstanceOf(ApiException.class);
    }
}
