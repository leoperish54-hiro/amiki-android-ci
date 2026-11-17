package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class SentinelStateMachineTest {
    @Test
    public void startupSuccessBecomesHealthyAndReachable() {
        SentinelStateMachine machine = new SentinelStateMachine();
        SentinelStateMachine.Transition transition = machine.onHealthSuccess();

        assertEquals(SentinelStateMachine.State.HEALTHY, transition.getState());
        assertEquals(SentinelStateMachine.EventType.BACKEND_REACHABLE, transition.getEventType());
        assertFalse(transition.shouldAttemptRecovery());
    }

    @Test
    public void repeatedFailuresEscalateOnlyAtThreshold() {
        SentinelStateMachine machine = new SentinelStateMachine();

        SentinelStateMachine.Transition first = machine.onHealthFailure();
        SentinelStateMachine.Transition second = machine.onHealthFailure();
        SentinelStateMachine.Transition third = machine.onHealthFailure();

        assertEquals(SentinelStateMachine.State.DEGRADED, first.getState());
        assertEquals(SentinelStateMachine.EventType.API_HEALTH_CHANGED, first.getEventType());
        assertEquals(SentinelStateMachine.State.DEGRADED, second.getState());
        assertEquals(SentinelStateMachine.State.UNREACHABLE, third.getState());
        assertEquals(SentinelStateMachine.EventType.BACKEND_UNREACHABLE, third.getEventType());
        assertTrue(third.shouldAttemptRecovery());
    }

    @Test
    public void recoveryIsBoundedAndExhaustsAfterConfiguredAttempts() {
        SentinelStateMachine machine = new SentinelStateMachine(1, 2, 1, 0);

        SentinelStateMachine.Transition failed = machine.onHealthFailure();
        assertTrue(failed.shouldAttemptRecovery());

        assertEquals(
                SentinelStateMachine.EventType.RECOVERY_STARTED,
                machine.startRecovery().getEventType()
        );
        assertEquals(
                SentinelStateMachine.EventType.RECOVERY_FAILED,
                machine.onRecoveryCommandResult(false).getEventType()
        );
        assertTrue(machine.onHealthFailure().shouldAttemptRecovery());

        assertEquals(
                SentinelStateMachine.EventType.RECOVERY_STARTED,
                machine.startRecovery().getEventType()
        );
        SentinelStateMachine.Transition exhausted =
                machine.onRecoveryCommandResult(false);

        assertEquals(SentinelStateMachine.EventType.RECOVERY_FAILED, exhausted.getEventType());
        assertTrue(exhausted.isAutomaticRecoveryExhausted());
        assertEquals(SentinelStateMachine.EventType.NONE, machine.startRecovery().getEventType());
    }

    @Test
    public void acceptedRecoveryMustWaitThroughBackoffBeforeRetrying() {
        SentinelStateMachine machine = new SentinelStateMachine(3, 3, 3, 2);
        machine.onHealthFailure();
        machine.onHealthFailure();
        assertTrue(machine.onHealthFailure().shouldAttemptRecovery());
        assertEquals(SentinelStateMachine.State.RECOVERY, machine.startRecovery().getState());
        assertEquals(SentinelStateMachine.State.VERIFYING,
                machine.onRecoveryCommandResult(true).getState());

        assertFalse(machine.onHealthFailure().shouldAttemptRecovery());
        assertFalse(machine.onHealthFailure().shouldAttemptRecovery());
        SentinelStateMachine.Transition enteringBackoff = machine.onHealthFailure();
        assertEquals(SentinelStateMachine.State.BACKOFF, enteringBackoff.getState());
        assertFalse("A failed verification must not immediately start another recovery.",
                enteringBackoff.shouldAttemptRecovery());

        assertFalse(machine.onHealthFailure().shouldAttemptRecovery());
        assertFalse(machine.onHealthFailure().shouldAttemptRecovery());
        SentinelStateMachine.Transition afterBackoff = machine.onHealthFailure();
        assertEquals(SentinelStateMachine.State.UNREACHABLE, afterBackoff.getState());
        assertTrue("A retry may be requested only after backoff expires.",
                afterBackoff.shouldAttemptRecovery());
    }

    @Test
    public void finalRecoveryAttemptExhaustsWhenVerificationNeverSucceeds() {
        SentinelStateMachine machine = new SentinelStateMachine(1, 1, 3, 1);
        assertTrue(machine.onHealthFailure().shouldAttemptRecovery());
        machine.startRecovery();
        machine.onRecoveryCommandResult(true);
        machine.onHealthFailure();
        machine.onHealthFailure();
        SentinelStateMachine.Transition enteringBackoff = machine.onHealthFailure();
        assertEquals(SentinelStateMachine.State.BACKOFF, enteringBackoff.getState());
        assertTrue(enteringBackoff.isAutomaticRecoveryExhausted());
        assertFalse(enteringBackoff.shouldAttemptRecovery());
        machine.onHealthFailure();
        SentinelStateMachine.Transition exhausted = machine.onHealthFailure();
        assertEquals(SentinelStateMachine.State.UNREACHABLE, exhausted.getState());
        assertTrue(exhausted.isAutomaticRecoveryExhausted());
        assertFalse(exhausted.shouldAttemptRecovery());
    }

    @Test
    public void healthyHealthCheckConfirmsRecovery() {
        SentinelStateMachine machine = new SentinelStateMachine(1, 2, 2, 1);
        machine.onHealthFailure();
        machine.startRecovery();
        machine.onRecoveryCommandResult(true);

        SentinelStateMachine.Transition transition = machine.onHealthSuccess();

        assertEquals(SentinelStateMachine.State.HEALTHY, transition.getState());
        assertEquals(SentinelStateMachine.EventType.BACKEND_RECOVERED, transition.getEventType());
        assertEquals(0, transition.getConsecutiveFailures());
        assertEquals(0, transition.getRecoveryAttempts());
    }
}
