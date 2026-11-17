package com.amiki.app;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class UiActionDispatcherTest {
    private UiNodeSnapshot snapshot(UiNodeSnapshot.Node... nodes) {
        return new UiNodeSnapshot("com.example.app", 7, 0L, Arrays.asList(nodes));
    }

    private UiNodeSnapshot.Node button(String label) {
        return button(label, true);
    }

    private UiNodeSnapshot.Node button(String label, boolean visible) {
        return new UiNodeSnapshot.Node(0, -1, "android.widget.Button", label, "", "",
                true, false, false, false, 10, 20, 110, 70, visible);
    }

    @Test public void tapRequestIsTypedAndBoundToExactScreenAndTarget() {
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-001", snapshot(button("Search")), "Search",
                UiActionDispatcher.ExpectedUiState.textVisible("Results"), 0L);
        assertEquals(UiActionDispatcher.ActionType.TAP, request.actionType);
        assertEquals("com.example.app", request.packageName);
        assertEquals(7, request.windowId);
        assertEquals(64, request.snapshotFingerprint.length());
        assertEquals(64, request.targetFingerprint.length());
        assertEquals(64, request.transactionHash().length());
        assertFalse(request.toCanonicalJson().contains("Search"));
        assertFalse(request.toCanonicalJson().contains("Results"));
        assertEquals("text_visible", request.postconditionType);
        assertEquals(64, request.postconditionSha256.length());
    }

    @Test public void typeTextRequestSendsOnlySaltedDigestNotPlaintext() {
        UiNodeSnapshot.Node field = new UiNodeSnapshot.Node(0, -1,
                "android.widget.EditText", "private value", "", "com.example:id/search",
                true, false, true, false, 0, 0, 100, 50);
        UiActionDispatcher.Request request = UiActionDispatcher.typeText(
                "task-002", snapshot(field), "com.example:id/search", "Lucid Dreams", 0L);
        assertEquals(UiActionDispatcher.ActionType.TYPE_TEXT, request.actionType);
        assertNotNull(request.payloadSha256);
        assertEquals(64, request.payloadSha256.length());
        assertFalse(request.toCanonicalJson().contains("Lucid Dreams"));
        assertFalse(request.toCanonicalJson().contains("private value"));
        assertTrue(request.matchesTypedText("Lucid Dreams"));
        assertFalse(request.matchesTypedText("Different text"));
    }

    @Test public void textEntryCannotUseAnIncompleteAccessibilitySnapshot() {
        UiNodeSnapshot.Node field = new UiNodeSnapshot.Node(0, -1,
                "android.widget.EditText", "", "", "com.example:id/search",
                true, false, true, false, 0, 0, 100, 50);
        UiNodeSnapshot partial = new UiNodeSnapshot(
                "com.example.app", 7, 0L, Arrays.asList(field), false);
        try {
            UiActionDispatcher.typeText("task-incomplete-text", partial,
                    "com.example:id/search", "query", 0L);
            fail("text actions cannot use incomplete UI trees");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    @Test public void passwordFieldsCannotBecomeTypeActions() {
        UiNodeSnapshot.Node password = new UiNodeSnapshot.Node(0, -1,
                "android.widget.EditText", "secret", "", "com.example:id/password",
                true, false, true, true, 0, 0, 100, 50);
        try {
            UiActionDispatcher.typeText("task-003", snapshot(password),
                    "com.example:id/password", "do not send", 0L);
            fail("password field must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not be resolved"));
        }
    }

    @Test public void freshWindowAndSnapshotMustMatchBeforeExecution() {
        UiNodeSnapshot original = snapshot(button("Continue"));
        UiActionDispatcher.Request request = UiActionDispatcher.tap("task-004", original, "Continue",
                UiActionDispatcher.ExpectedUiState.textVisible("Next screen"), 0L);
        assertTrue(request.matchesFreshSnapshot(original, 0L));
        UiNodeSnapshot otherWindow = new UiNodeSnapshot("com.example.app", 8, 0L,
                Arrays.asList(button("Continue")));
        assertFalse(request.matchesFreshSnapshot(otherWindow, 0L));
        UiNodeSnapshot otherPackage = new UiNodeSnapshot("com.other.app", 7, 0L,
                Arrays.asList(button("Continue")));
        assertFalse(request.matchesFreshSnapshot(otherPackage, 0L));
    }

    @Test public void scrollAndBackAreDistinctTypedActions() {
        UiNodeSnapshot.Node scrollNode = new UiNodeSnapshot.Node(0, -1,
                "android.widget.ScrollView", "", "", "com.example:id/list",
                false, true, false, false, 0, 0, 100, 300);
        UiNodeSnapshot scrollSnapshot = new UiNodeSnapshot(
                "com.example.app", 7, 0L, java.util.Collections.singletonList(scrollNode));
        UiActionDispatcher.Request forward = UiActionDispatcher.scroll(
                "task-scroll", scrollSnapshot, true,
                UiActionDispatcher.ExpectedUiState.textVisible("Last item"), 0L);
        UiActionDispatcher.Request back = UiActionDispatcher.back(
                "task-back", scrollSnapshot,
                UiActionDispatcher.ExpectedUiState.textVisible("Previous page"), 0L);
        assertEquals(UiActionDispatcher.ActionType.SCROLL_FORWARD, forward.actionType);
        assertEquals(UiActionDispatcher.ActionType.BACK, back.actionType);
        assertEquals(7, forward.windowId);
        assertEquals(7, back.windowId);
    }

    @Test public void transitionObservationDoesNotMistakeUnchangedScreenForSuccess() {
        UiNodeSnapshot before = snapshot(button("Play"));
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-transition", before, "Play",
                UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
        UiNodeSnapshot same = snapshot(button("Play"));
        UiNodeSnapshot changed = snapshot(button("Pause"));
        assertFalse(request.hasObservedUiChange(same, 0L));
        assertTrue(request.hasObservedUiChange(changed, 0L));
        UiNodeSnapshot wrongWindow = new UiNodeSnapshot("com.example.app", 8, 0L,
                Arrays.asList(button("Play")));
        assertTrue(request.hasObservedUiChange(wrongWindow, 0L));
    }

    @Test public void tapVerificationRequiresTheExpectedLabelToAppearAsATransition() {
        UiNodeSnapshot before = snapshot(button("Play"));
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-semantic-visible", before, "Play",
                UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
        assertTrue(request.matchesPostcondition(snapshot(button("Pause")), 0L));
        assertTrue(request.matchesPostcondition(snapshot(button("Play"), button("Pause")), 0L));
        assertFalse(request.matchesPostcondition(snapshot(button("Play")), 0L));
    }

    @Test public void semanticVisibilityIgnoresOffscreenNodes() {
        UiNodeSnapshot before = snapshot(button("Play"));
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-visible-only", before, "Play",
                UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
        assertFalse(request.matchesPostcondition(
                snapshot(button("Pause", false)), 0L));
        assertTrue(request.matchesPostcondition(
                snapshot(button("Pause", true)), 0L));
    }

    @Test public void semanticVerificationRejectsIncompleteAccessibilityTrees() {
        UiNodeSnapshot before = new UiNodeSnapshot(
                "com.example.app", 7, 0L, Arrays.asList(button("Play")), false);
        try {
            UiActionDispatcher.tap("task-incomplete", before, "Play",
                    UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
            fail("incomplete trees cannot authorize semantic actions");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("incomplete"));
        }
    }

    @Test public void alreadyVisibleExpectedLabelDoesNotCountAsANewTransition() {
        UiNodeSnapshot before = snapshot(button("Play"), button("Pause"));
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-already-visible", before, "Play",
                UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
        assertFalse(request.matchesPostcondition(snapshot(button("Play"), button("Pause")), 0L));
    }

    @Test public void absentPostconditionRequiresTheLabelToDisappear() {
        UiNodeSnapshot before = snapshot(button("Dismiss"), button("Dialog"));
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-semantic-absent", before, "Dismiss",
                UiActionDispatcher.ExpectedUiState.textAbsent("Dialog"), 0L);
        assertTrue(request.matchesPostcondition(snapshot(button("Dismiss")), 0L));
        assertFalse(request.matchesPostcondition(snapshot(button("Dismiss"), button("Dialog")), 0L));
    }

    @Test(expected = IllegalArgumentException.class)
    public void nonTextActionsCannotBeCreatedWithoutASemanticPostcondition() {
        UiActionDispatcher.tap("task-no-postcondition", snapshot(button("Play")), "Play", null, 0L);
    }

    @Test public void authorizationReceiptMustMatchEntireActionAndRemainUnexpired() throws Exception {
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-005", snapshot(button("Play")), "Play",
                UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
        JSONObject receipt = new JSONObject();
        receipt.put("status", "authorized");
        receipt.put("action_id", request.actionId);
        receipt.put("task_id", request.taskId);
        receipt.put("action_type", request.actionType.wireValue);
        receipt.put("package_name", request.packageName);
        receipt.put("window_id", request.windowId);
        receipt.put("snapshot_fingerprint", request.snapshotFingerprint);
        receipt.put("target_fingerprint", request.targetFingerprint);
        receipt.put("payload_sha256", JSONObject.NULL);
        receipt.put("postcondition_type", request.postconditionType);
        receipt.put("postcondition_sha256", request.postconditionSha256);
        receipt.put("cipher_audit_event_id", request.actionId + ".authorized");
        receipt.put("cipher_audit_sequence", 1L);
        receipt.put("cipher_audit_hash", "a".repeat(64));
        receipt.put("expires_at", System.currentTimeMillis() / 1000L + 5);
        assertTrue(request.matchesReceipt(receipt));
        receipt.put("package_name", "com.attacker.app");
        assertFalse(request.matchesReceipt(receipt));
        receipt.put("package_name", request.packageName);
        receipt.put("postcondition_sha256", request.postconditionSha256);
        receipt.put("cipher_audit_sequence", 0L);
        assertFalse(request.matchesReceipt(receipt));
        receipt.put("cipher_audit_sequence", 1L);
        receipt.put("cipher_audit_event_id", "forged.authorized");
        assertFalse(request.matchesReceipt(receipt));
        receipt.put("cipher_audit_event_id", request.actionId + ".authorized");
        receipt.put("postcondition_sha256", "f".repeat(64));
        assertFalse(request.matchesReceipt(receipt));
    }
}
