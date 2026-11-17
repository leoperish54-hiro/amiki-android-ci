package com.amiki.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Pure state machine for Sentinel health/recovery behavior. */
public final class SentinelStateMachine {
    public enum State {
        UNKNOWN,
        HEALTHY,
        DEGRADED,
        UNREACHABLE,
        RECOVERY,
        VERIFYING,
        BACKOFF
    }

    public enum EventType {
        NONE,
        API_HEALTH_CHANGED,
        BACKEND_REACHABLE,
        BACKEND_UNREACHABLE,
        BACKEND_RECOVERED,
        RECOVERY_STARTED,
        RECOVERY_SUCCESS,
        RECOVERY_FAILED
    }

    public static final class Transition {
        private final State state;
        private final EventType eventType;
        private final boolean shouldAttemptRecovery;
        private final boolean automaticRecoveryExhausted;
        private final int consecutiveFailures;
        private final int recoveryAttempts;

        Transition(
                State state,
                EventType eventType,
                boolean shouldAttemptRecovery,
                boolean automaticRecoveryExhausted,
                int consecutiveFailures,
                int recoveryAttempts
        ) {
            this.state = state;
            this.eventType = eventType;
            this.shouldAttemptRecovery = shouldAttemptRecovery;
            this.automaticRecoveryExhausted = automaticRecoveryExhausted;
            this.consecutiveFailures = consecutiveFailures;
            this.recoveryAttempts = recoveryAttempts;
        }

        public State getState() { return state; }
        public EventType getEventType() { return eventType; }
        public boolean shouldAttemptRecovery() { return shouldAttemptRecovery; }
        public boolean isAutomaticRecoveryExhausted() { return automaticRecoveryExhausted; }
        public int getConsecutiveFailures() { return consecutiveFailures; }
        public int getRecoveryAttempts() { return recoveryAttempts; }
    }

    private final int failureThreshold;
    private final int maxRecoveryAttempts;
    private final int verifyFailureThreshold;
    private final int backoffPolls;

    private State state = State.UNKNOWN;
    private int consecutiveFailures = 0;
    private int recoveryAttempts = 0;
    private int verifyFailures = 0;
    private int remainingBackoffPolls = 0;
    private boolean automaticRecoveryExhausted = false;

    public SentinelStateMachine() {
        this(3, 3, 3, 2);
    }

    public SentinelStateMachine(
            int failureThreshold,
            int maxRecoveryAttempts,
            int verifyFailureThreshold,
            int backoffPolls
    ) {
        if (failureThreshold < 1 || maxRecoveryAttempts < 1
                || verifyFailureThreshold < 1 || backoffPolls < 0) {
            throw new IllegalArgumentException("Sentinel thresholds must be positive.");
        }
        this.failureThreshold = failureThreshold;
        this.maxRecoveryAttempts = maxRecoveryAttempts;
        this.verifyFailureThreshold = verifyFailureThreshold;
        this.backoffPolls = backoffPolls;
    }

    public synchronized Transition onHealthSuccess() {
        boolean recovered = state != State.UNKNOWN && state != State.HEALTHY;
        state = State.HEALTHY;
        consecutiveFailures = 0;
        recoveryAttempts = 0;
        verifyFailures = 0;
        remainingBackoffPolls = 0;
        automaticRecoveryExhausted = false;

        EventType event = State.HEALTHY == state && !recovered
                ? EventType.BACKEND_REACHABLE
                : EventType.BACKEND_RECOVERED;
        return transition(event, false);
    }

    public synchronized Transition onHealthFailure() {
        consecutiveFailures++;

        if (state == State.RECOVERY || state == State.VERIFYING) {
            verifyFailures++;
            if (verifyFailures < verifyFailureThreshold) {
                state = State.VERIFYING;
                return transition(EventType.API_HEALTH_CHANGED, false);
            }
            state = State.BACKOFF;
            remainingBackoffPolls = backoffPolls;
            // Do not let the caller start another recovery while entering
            // backoff. The last allowed attempt is exhausted if health never
            // returns after its verification window.
            if (recoveryAttempts >= maxRecoveryAttempts) {
                automaticRecoveryExhausted = true;
            }
            return transition(EventType.BACKEND_UNREACHABLE, false);
        } else if (state == State.BACKOFF) {
            if (remainingBackoffPolls > 0) {
                remainingBackoffPolls--;
                return transition(EventType.API_HEALTH_CHANGED, false);
            }
            state = State.UNREACHABLE;
        } else if (consecutiveFailures >= failureThreshold) {
            state = State.UNREACHABLE;
        } else {
            state = State.DEGRADED;
            return transition(EventType.API_HEALTH_CHANGED, false);
        }

        boolean shouldRecover = !automaticRecoveryExhausted
                && recoveryAttempts < maxRecoveryAttempts;
        return transition(
                EventType.BACKEND_UNREACHABLE,
                shouldRecover
        );
    }

    public synchronized Transition startRecovery() {
        if (automaticRecoveryExhausted || recoveryAttempts >= maxRecoveryAttempts) {
            automaticRecoveryExhausted = true;
            state = State.UNREACHABLE;
            return transition(EventType.NONE, false);
        }

        recoveryAttempts++;
        state = State.RECOVERY;
        verifyFailures = 0;
        return transition(EventType.RECOVERY_STARTED, false);
    }

    public synchronized Transition onRecoveryCommandResult(boolean accepted) {
        if (accepted) {
            state = State.VERIFYING;
            verifyFailures = 0;
            return transition(EventType.RECOVERY_SUCCESS, false);
        }

        state = State.BACKOFF;
        remainingBackoffPolls = backoffPolls;
        if (recoveryAttempts >= maxRecoveryAttempts) {
            automaticRecoveryExhausted = true;
        }
        return transition(EventType.RECOVERY_FAILED, false);
    }

    public synchronized State getState() {
        return state;
    }

    public synchronized int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public synchronized int getRecoveryAttempts() {
        return recoveryAttempts;
    }

    public synchronized boolean isAutomaticRecoveryExhausted() {
        return automaticRecoveryExhausted;
    }

    private Transition transition(EventType eventType, boolean shouldAttemptRecovery) {
        return new Transition(
                state,
                eventType,
                shouldAttemptRecovery,
                automaticRecoveryExhausted,
                consecutiveFailures,
                recoveryAttempts
        );
    }
}
