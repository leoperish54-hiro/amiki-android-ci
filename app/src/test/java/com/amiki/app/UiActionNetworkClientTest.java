package com.amiki.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UiActionNetworkClientTest {
    private static UiNodeSnapshot snapshot() {
        UiNodeSnapshot.Node play = new UiNodeSnapshot.Node(
                0, -1, "android.widget.Button", "Play", "", "",
                true, false, false, false, 10, 20, 110, 70);
        return new UiNodeSnapshot("com.example.app", 7, 0L,
                Arrays.asList(play), true);
    }

    private static JSONObject validReceipt(UiActionDispatcher.Request request,
                                           String resultCode) throws Exception {
        JSONObject receipt = new JSONObject();
        receipt.put("status", "recorded");
        receipt.put("action_id", request.actionId);
        receipt.put("result_code", resultCode);
        receipt.put("cipher_audit_event_id", request.actionId + ".outcome");
        receipt.put("cipher_audit_sequence", 42L);
        receipt.put("cipher_audit_hash", "a".repeat(64));
        return receipt;
    }

    @Test
    public void outcomeReceiptRequiresMatchingCipherEventSequenceAndHash() throws Exception {
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-audit-receipt", snapshot(), "Play",
                UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
        JSONObject receipt = validReceipt(request, "outcome_verified");

        assertTrue(UiActionNetworkClient.isValidOutcomeReceipt(
                receipt, request, "outcome_verified"));

        receipt.put("cipher_audit_event_id", "different-action.outcome");
        assertFalse(UiActionNetworkClient.isValidOutcomeReceipt(
                receipt, request, "outcome_verified"));

        receipt.put("cipher_audit_event_id", request.actionId + ".outcome");
        receipt.put("cipher_audit_sequence", 0L);
        assertFalse(UiActionNetworkClient.isValidOutcomeReceipt(
                receipt, request, "outcome_verified"));

        receipt.put("cipher_audit_sequence", 42L);
        receipt.put("cipher_audit_hash", "not-a-digest");
        assertFalse(UiActionNetworkClient.isValidOutcomeReceipt(
                receipt, request, "outcome_verified"));

        receipt.put("cipher_audit_hash", "a".repeat(64));
        assertFalse(UiActionNetworkClient.isValidOutcomeReceipt(
                receipt, request, "execution_failed"));
    }

    @Test
    public void outcomeReceiptRejectsWrongActionAndUnrecordedStatus() throws Exception {
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                "task-audit-receipt-negative", snapshot(), "Play",
                UiActionDispatcher.ExpectedUiState.textVisible("Pause"), 0L);
        JSONObject receipt = validReceipt(request, "outcome_unverified");

        receipt.put("action_id", "another-action");
        assertFalse(UiActionNetworkClient.isValidOutcomeReceipt(
                receipt, request, "outcome_unverified"));

        receipt.put("action_id", request.actionId);
        receipt.put("status", "accepted");
        assertFalse(UiActionNetworkClient.isValidOutcomeReceipt(
                receipt, request, "outcome_unverified"));
    }
}
