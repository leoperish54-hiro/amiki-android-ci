package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DeviceTaskControllerTest {

    @Test
    public void taskRequiresObservationAndApprovalBeforeExecution() {
        DeviceTaskController task = new DeviceTaskController("spotify-play-1");
        assertEquals(DeviceTaskController.State.QUEUED, task.snapshot().state);

        task.beginObservation();
        task.requestOwnerApproval("Play Lucid Dreams by Juice WRLD?");
        assertEquals(DeviceTaskController.State.AWAITING_OWNER_APPROVAL, task.snapshot().state);

        task.ownerApproved();
        assertEquals(DeviceTaskController.State.EXECUTING, task.snapshot().state);
        assertTrue(task.mayStartNextStep());
    }

    @Test
    public void stopPreventsAnyFurtherStep() {
        DeviceTaskController task = new DeviceTaskController("whatsapp-send-1");
        task.beginObservation();
        task.beginExecution();
        task.stop();

        assertEquals(DeviceTaskController.State.CANCELLED, task.snapshot().state);
        assertTrue(task.snapshot().stopRequested);
        assertFalse(task.mayStartNextStep());
    }

    @Test
    public void completionRequiresVerification() {
        DeviceTaskController task = new DeviceTaskController("spotify-play-2");
        task.beginObservation();
        task.beginExecution();
        task.beginVerification();
        task.complete("Playback state reports the requested track.");

        assertEquals(DeviceTaskController.State.COMPLETED, task.snapshot().state);
        assertEquals(0, task.snapshot().completedSteps);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotCompleteWithoutVerification() {
        DeviceTaskController task = new DeviceTaskController("whatsapp-send-2");
        task.beginObservation();
        task.beginExecution();
        task.complete("Sent");
    }

    @Test
    public void completedStepRequiresFreshObservationBeforeNextExecution() {
        DeviceTaskController task = new DeviceTaskController("multi-step-1");
        task.beginObservation();
        task.beginExecution();
        task.stepCompleted("Opened target app.");

        assertEquals(DeviceTaskController.State.OBSERVING, task.snapshot().state);
        assertEquals(1, task.snapshot().completedSteps);
        assertFalse(task.mayStartNextStep());

        task.beginExecution();
        assertTrue(task.mayStartNextStep());
    }

    @Test
    public void biometricAuthorizationFailureReturnsToOwnerInputState() {
        DeviceTaskController task = new DeviceTaskController("authorization-failure-1");
        task.beginObservation();
        task.requestOwnerApproval("Tap Continue");
        task.ownerApprovalFailed("Core authorization service is unavailable.");

        assertEquals(DeviceTaskController.State.NEEDS_OWNER_INPUT, task.snapshot().state);
        assertFalse(task.snapshot().stopRequested);
        task.beginObservation();
        assertEquals(DeviceTaskController.State.OBSERVING, task.snapshot().state);
    }

    @Test
    public void verifiedActionStepReturnsToObservationWithoutCompletingMission() {
        DeviceTaskController task = new DeviceTaskController("verified-step-1");
        task.beginObservation();
        task.requestOwnerApproval("Scroll forward");
        task.ownerApproved();
        task.beginVerification();
        task.verificationStepCompleted("Visible result list changed after scroll.");

        assertEquals(DeviceTaskController.State.OBSERVING, task.snapshot().state);
        assertEquals(1, task.snapshot().completedSteps);
        assertFalse(task.mayStartNextStep());
        assertFalse(task.snapshot().stopRequested);
    }

    @Test(expected = IllegalArgumentException.class)
    public void taskIdMustNotBeBlank() {
        new DeviceTaskController("   ");
    }
}
