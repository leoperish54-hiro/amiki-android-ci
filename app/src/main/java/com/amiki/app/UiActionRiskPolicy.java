package com.amiki.app;

import java.util.Locale;

/**
 * Local fail-closed guard for UI controls that may commit an external or
 * destructive action. Label heuristics are intentionally conservative:
 * unknown or ambiguous controls must not be treated as harmless.
 */
final class UiActionRiskPolicy {
    private static final String[] COMMIT_LABEL_PREFIXES = {
            "send", "submit", "post", "publish", "pay", "make payment",
            "transfer", "delete", "remove", "purchase", "buy", "place order",
            "submit order", "confirm order", "checkout", "withdraw", "cash out",
            "confirm", "approve", "authorize", "revoke", "erase", "wipe", "reset",
            "cancel subscription", "book appointment", "book now", "schedule", "share"
    };

    private UiActionRiskPolicy() { }

    /**
     * Returns true when a tap target looks like a commit/destructive control
     * and therefore needs a dedicated contextual owner preview before Amiki
     * may operate it. This intentionally favours false positives over an
     * unreviewed external side effect.
     */
    static boolean requiresContextualOwnerReview(String targetLabel) {
        String normalized = normalize(targetLabel);
        if (normalized.isEmpty()) return true;

        for (String prefix : COMMIT_LABEL_PREFIXES) {
            if (normalized.equals(prefix) || normalized.startsWith(prefix + " ")) {
                return true;
            }
        }
        return false;
    }

    static String ownerReviewMessage() {
        return "Amiki paused before a control that may send, publish, pay, transfer, "
                + "delete or otherwise commit an external action. A generic tap approval "
                + "does not show enough context to verify the exact recipient, content or "
                + "destination. Review the target app and complete this commit manually. "
                + "No tap was performed.";
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim()
                .replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
