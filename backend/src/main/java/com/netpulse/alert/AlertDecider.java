package com.netpulse.alert;

import com.netpulse.alert.AlertTypes.State;
import com.netpulse.common.error.ApiException;

import java.time.Duration;
import java.time.Instant;

/**
 * The alert state machine, with no I/O so every path is unit tested.
 *
 * <pre>
 *        condition held for the rule's duration              operator
 *  (ok) ----------------------------------------> TRIGGERED ---------> ACKNOWLEDGED
 *                                                     |                     |
 *                                                     +----- condition -----+---> RESOLVED
 *                                                            clears
 * </pre>
 *
 * <p>NO_DATA is a separate input from CLEAR on purpose. When ping stops answering there is no RTT at all, but
 * that doesn't mean latency recovered, so a high-latency alert must stay open instead of falsely resolving.
 */
public final class AlertDecider {
    private AlertDecider() { }

    public enum Signal { BREACH, CLEAR, NO_DATA }

    public enum Action { NONE, START_PENDING, TRIGGER, CLEAR_PENDING, RESOLVE }

    /**
     * @param open         whether an unresolved alert exists for this condition
     * @param pendingSince when the condition started breaching, if it is being timed
     * @param muted        the device is in a maintenance window: nothing new may fire
     */
    public static Action decide(Signal signal, boolean open, Instant pendingSince, int durationSeconds, Instant now, boolean muted) {
        return switch (signal) {
            case NO_DATA -> Action.NONE;
            case CLEAR -> open ? Action.RESOLVE : pendingSince != null ? Action.CLEAR_PENDING : Action.NONE;
            case BREACH -> {
                if (open) yield Action.NONE;
                if (muted) yield pendingSince != null ? Action.CLEAR_PENDING : Action.NONE;   // the clock restarts after maintenance
                if (durationSeconds == 0) yield Action.TRIGGER;
                if (pendingSince == null) yield Action.START_PENDING;
                yield Duration.between(pendingSince, now).getSeconds() >= durationSeconds ? Action.TRIGGER : Action.NONE;
            }
        };
    }

    /** @throws ApiException 409 unless the alert is in TRIGGERED, the only state that can be acknowledged */
    public static void requireAcknowledgeable(State current) {
        if (current != State.TRIGGERED) {
            throw ApiException.conflict(current == State.ACKNOWLEDGED ? "This alert is already acknowledged." : "This alert is already resolved.");
        }
    }
}
