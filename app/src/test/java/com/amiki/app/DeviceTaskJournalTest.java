package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class DeviceTaskJournalTest {

    @Test
    public void persistsOnlyMetadataAndIntentDigest() {
        DeviceTaskJournal.MemoryStore store = new DeviceTaskJournal.MemoryStore();
        DeviceTaskJournal journal = new DeviceTaskJournal(store);

        DeviceTaskJournal.Record first = journal.beginOrResume(
                "Send Joshua: meet me at ten", 1000L);

        assertNotNull(first);
        assertEquals(DeviceTaskJournal.State.RUNNING, first.state);
        assertFalse(store.read().contains("Send Joshua"));
        assertFalse(store.read().contains("Joshua"));
        assertTrue(first.intentSha256.matches("[0-9a-f]{64}"));
    }

    @Test
    public void verifiedActionCanResumeFromPersistedMetadata() {
        DeviceTaskJournal.MemoryStore store = new DeviceTaskJournal.MemoryStore();
        DeviceTaskJournal initial = new DeviceTaskJournal(store);
        DeviceTaskJournal.Record first = initial.beginOrResume("Search YouTube", 1000L);
        initial.observed("com.google.android.youtube", 1100L);
        initial.actionPending("action-001", 1200L);
        initial.actionResult("action-001", "outcome_verified", 1600L);

        DeviceTaskJournal recovered = new DeviceTaskJournal(store);
        DeviceTaskJournal.Record restarted = recovered.recoverAfterProcessRestart(2000L);
        assertEquals(DeviceTaskJournal.State.INTERRUPTED, restarted.state);
        assertEquals(1, restarted.stepIndex);

        DeviceTaskJournal.Record resumed = recovered.beginOrResume("Search YouTube", 2100L);
        assertEquals(first.taskId, resumed.taskId);
        assertEquals(1, resumed.stepIndex);
        assertEquals(DeviceTaskJournal.State.RUNNING, resumed.state);
    }

    @Test
    public void inFlightActionAfterCrashIsNeverReplayed() {
        DeviceTaskJournal.MemoryStore store = new DeviceTaskJournal.MemoryStore();
        DeviceTaskJournal initial = new DeviceTaskJournal(store);
        DeviceTaskJournal.Record first = initial.beginOrResume("Send a message", 1000L);
        initial.actionPending("action-unknown", 1100L);

        DeviceTaskJournal recovered = new DeviceTaskJournal(store);
        DeviceTaskJournal.Record restarted = recovered.recoverAfterProcessRestart(1500L);
        assertEquals(DeviceTaskJournal.State.NEEDS_OWNER_INPUT, restarted.state);
        assertEquals("outcome_unknown", restarted.lastActionResult);
        assertFalse(restarted.actionInFlight);
        assertEquals(0, restarted.stepIndex);

        DeviceTaskJournal.Record sameIntent = recovered.beginOrResume("Send a message", 1700L);
        assertEquals(first.taskId, sameIntent.taskId);
        assertEquals(DeviceTaskJournal.State.NEEDS_OWNER_INPUT, sameIntent.state);
    }

    @Test
    public void unverifiedActionPausesInsteadOfAdvancing() {
        DeviceTaskJournal.MemoryStore store = new DeviceTaskJournal.MemoryStore();
        DeviceTaskJournal journal = new DeviceTaskJournal(store);
        journal.beginOrResume("Open an app", 1000L);
        journal.actionPending("action-002", 1100L);
        journal.actionResult("action-002", "outcome_unverified", 1500L);

        assertEquals(DeviceTaskJournal.State.NEEDS_OWNER_INPUT, journal.current().state);
        assertEquals(0, journal.current().stepIndex);
        assertEquals("outcome_unverified", journal.current().lastActionResult);
    }

    @Test(expected = IllegalStateException.class)
    public void cannotRecordOutcomeForDifferentActionId() {
        DeviceTaskJournal journal = new DeviceTaskJournal(new DeviceTaskJournal.MemoryStore());
        journal.beginOrResume("Open settings", 1000L);
        journal.actionPending("action-real", 1100L);
        journal.actionResult("action-forged", "outcome_verified", 1200L);
    }

    @Test
    public void stoppingBeforeAnyActionCancelsWithoutOwnerReview() {
        DeviceTaskJournal journal = new DeviceTaskJournal(new DeviceTaskJournal.MemoryStore());
        journal.beginOrResume("Open Spotify", 1000L);
        journal.cancel(1100L);

        assertEquals(DeviceTaskJournal.State.CANCELLED, journal.current().state);
    }

    @Test
    public void stoppingDuringInFlightActionPreservesUnknownOutcomeGate() {
        DeviceTaskJournal journal = new DeviceTaskJournal(new DeviceTaskJournal.MemoryStore());
        journal.beginOrResume("Send a message", 1000L);
        journal.actionPending("action-stop-race", 1100L);
        journal.cancel(1150L);

        assertEquals(DeviceTaskJournal.State.NEEDS_OWNER_INPUT, journal.current().state);
        assertEquals("outcome_unknown", journal.current().lastActionResult);
        try {
            journal.beginOrResume("Open Chrome", 1200L);
            org.junit.Assert.fail("Stopping an in-flight action must preserve the owner review gate.");
        } catch (IllegalStateException expected) {
            assertEquals(DeviceTaskJournal.State.NEEDS_OWNER_INPUT, journal.current().state);
        }
    }

    @Test
    public void unresolvedTaskBlocksDifferentIntentUntilOwnerReview() {
        DeviceTaskJournal journal = new DeviceTaskJournal(new DeviceTaskJournal.MemoryStore());
        journal.beginOrResume("Send a message", 1000L);
        journal.actionPending("action-unknown", 1100L);
        journal.recoverAfterProcessRestart(1200L);

        try {
            journal.beginOrResume("Open Chrome", 1300L);
            org.junit.Assert.fail("A new task must not hide an unresolved external action.");
        } catch (IllegalStateException expected) {
            assertEquals(DeviceTaskJournal.State.NEEDS_OWNER_INPUT, journal.current().state);
        }
    }

    @Test
    public void explicitOwnerReviewAllowsNewTaskWithoutMarkingOldActionSuccessful() {
        DeviceTaskJournal journal = new DeviceTaskJournal(new DeviceTaskJournal.MemoryStore());
        journal.beginOrResume("Send a message", 1000L);
        journal.actionPending("action-unknown", 1100L);
        journal.recoverAfterProcessRestart(1200L);
        journal.acknowledgeOwnerReview(1300L);

        assertEquals(DeviceTaskJournal.State.CANCELLED, journal.current().state);
        assertEquals("owner_reviewed_no_retry", journal.current().lastActionResult);
        DeviceTaskJournal.Record next = journal.beginOrResume("Open Chrome", 1400L);
        assertEquals(DeviceTaskJournal.State.RUNNING, next.state);
        assertEquals(0, next.stepIndex);
    }

    @Test
    public void explicitRepeatOfCompletedTaskCreatesANewTask() {
        DeviceTaskJournal journal = new DeviceTaskJournal(new DeviceTaskJournal.MemoryStore());
        String first = journal.beginOrResume("Open Spotify", 1000L).taskId;
        journal.complete(1100L);
        DeviceTaskJournal.Record second = journal.beginOrResume("Open Spotify", 1200L);

        assertNotEquals(first, second.taskId);
        assertEquals(DeviceTaskJournal.State.RUNNING, second.state);
        assertEquals(0, second.stepIndex);
    }

    @Test
    public void aDifferentIntentStartsANewTask() {
        DeviceTaskJournal journal = new DeviceTaskJournal(new DeviceTaskJournal.MemoryStore());
        String first = journal.beginOrResume("Open Spotify", 1000L).taskId;
        String second = journal.beginOrResume("Open Chrome", 1100L).taskId;
        assertNotEquals(first, second);
        assertEquals(0, journal.current().stepIndex);
    }
}
