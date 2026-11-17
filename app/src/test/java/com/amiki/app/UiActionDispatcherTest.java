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
        return new UiNodeSnapshot.Node(0, -1, "android.widget.Button", label, "", "",
                true, false, false, false, 10, 20, 110, 70);
    }

    @Test public void tapRequestIsTypedAndBoundToExactScreenAndTarget() {
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-001", snapshot(button("Search")), "Search");
        assertEquals(UiActionDispatcher.ActionType.TAP, request.actionType);
        assertEquals("com.example.app", request.packageName);
        assertEquals(7, request.windowId);
        assertEquals(64, request.snapshotFingerprint.length());
        assertEquals(64, request.targetFingerprint.length());
        assertEquals(64, request.transactionHash().length());
        assertFalse(request.toCanonicalJson().contains("Search"));
    }

    @Test public void typeTextRequestSendsOnlySaltedDigestNotPlaintext() {
        UiNodeSnapshot.Node field = new UiNodeSnapshot.Node(0, -1,
                "android.widget.EditText", "private value", "", "com.example:id/search",
                true, false, true, false, 0, 0, 100, 50);
        UiActionDispatcher.Request request = UiActionDispatcher.typeText(
                "task-002", snapshot(field), "com.example:id/search", "Lucid Dreams");
        assertEquals(UiActionDispatcher.ActionType.TYPE_TEXT, request.actionType);
        assertNotNull(request.payloadSha256);
        assertEquals(64, request.payloadSha256.length());
        assertFalse(request.toCanonicalJson().contains("Lucid Dreams"));
        assertFalse(request.toCanonicalJson().contains("private value"));
        assertTrue(request.matchesTypedText("Lucid Dreams"));
        assertFalse(request.matchesTypedText("Different text"));
    }

    @Test public void passwordFieldsCannotBecomeTypeActions() {
        UiNodeSnapshot.Node password = new UiNodeSnapshot.Node(0, -1,
                "android.widget.EditText", "secret", "", "com.example:id/password",
                true, false, true, true, 0, 0, 100, 50);
        try {
            UiActionDispatcher.typeText("task-003", snapshot(password),
                    "com.example:id/password", "do not send");
            fail("password field must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("not be resolved"));
        }
    }

    @Test public void freshWindowAndSnapshotMustMatchBeforeExecution() {
        UiNodeSnapshot original = snapshot(button("Continue"));
        UiActionDispatcher.Request request = UiActionDispatcher.tap("task-004", original, "Continue");
        assertTrue(request.matchesFreshSnapshot(original));
        UiNodeSnapshot otherWindow = new UiNodeSnapshot("com.example.app", 8, 0L,
                Arrays.asList(button("Continue")));
        assertFalse(request.matchesFreshSnapshot(otherWindow));
        UiNodeSnapshot otherPackage = new UiNodeSnapshot("com.other.app", 7, 0L,
                Arrays.asList(button("Continue")));
        assertFalse(request.matchesFreshSnapshot(otherPackage));
    }

    @Test public void authorizationReceiptMustMatchEntireActionAndRemainUnexpired() throws Exception {
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-005", snapshot(button("Play")), "Play");
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
        receipt.put("expires_at", System.currentTimeMillis() / 1000L + 5);
        assertTrue(request.matchesReceipt(receipt));
        receipt.put("package_name", "com.attacker.app");
        assertFalse(request.matchesReceipt(receipt));
    }
}
