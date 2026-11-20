package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class UiActionRiskPolicyTest {
    @Test
    public void commitAndDestructiveControlsRequireContextualOwnerReview() {
        String[] labels = {
                "Send", "Send message", "Submit form", "Submit order",
                "Publish post", "Pay now", "Make payment", "Transfer money",
                "Delete account", "Remove access", "Buy now", "Place order",
                "Checkout", "Withdraw", "Confirm payment", "Confirm order",
                "Approve request", "Authorize access", "Revoke access",
                "Erase data", "Wipe device", "Reset account",
                "Cancel subscription", "Book appointment", "Schedule payment",
                "Share document"
        };
        for (String label : labels) {
            assertTrue("Expected contextual review for: " + label,
                    UiActionRiskPolicy.requiresContextualOwnerReview(label));
        }
    }

    @Test
    public void ordinaryNavigationAndReadControlsRemainEligibleForNormalApproval() {
        String[] labels = {
                "Search", "Play", "Pause", "Continue", "Back",
                "Open settings", "Next page", "View details", "Refresh",
                "Payment settings", "Order history", "Book recommendations"
        };
        for (String label : labels) {
            assertFalse("Unexpected commit classification for: " + label,
                    UiActionRiskPolicy.requiresContextualOwnerReview(label));
        }
    }

    @Test
    public void normalizationIsCaseAndWhitespaceInsensitive() {
        assertTrue(UiActionRiskPolicy.requiresContextualOwnerReview("  SEND   MESSAGE  "));
        assertTrue(UiActionRiskPolicy.requiresContextualOwnerReview("DELETE"));
    }

    @Test
    public void missingLabelsFailClosed() {
        assertTrue(UiActionRiskPolicy.requiresContextualOwnerReview(null));
        assertTrue(UiActionRiskPolicy.requiresContextualOwnerReview("   "));
    }

    @Test
    public void prefixesDoNotMatchInsideUnrelatedWords() {
        assertFalse(UiActionRiskPolicy.requiresContextualOwnerReview("Sender information"));
        assertFalse(UiActionRiskPolicy.requiresContextualOwnerReview("Payment settings"));
        assertFalse(UiActionRiskPolicy.requiresContextualOwnerReview("Order history"));
    }
}
