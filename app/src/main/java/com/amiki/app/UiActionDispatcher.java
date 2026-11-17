package com.amiki.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import org.json.JSONObject;
import java.util.UUID;

/**
 * Typed, fail-closed AFDI action contract. It creates canonical request bodies
 * and binds each request to a one-use UOSA L2 transaction challenge.
 * Plain text is kept only in memory and represented remotely by a salted digest.
 */
public final class UiActionDispatcher {
    public static final String AUTHORIZE_PATH = "/api/v1/device/ui-actions/authorize";

    public enum ActionType {
        TAP("tap"), TYPE_TEXT("type_text"), SCROLL_FORWARD("scroll_forward"),
        SCROLL_BACKWARD("scroll_backward"), BACK("back");
        public final String wireValue;
        ActionType(String wireValue) { this.wireValue = wireValue; }
    }

    /** Explicit semantic UI transition required before a non-text action is reported verified. */
    public static final class ExpectedUiState {
        public enum Type {
            TEXT_VISIBLE("text_visible"), TEXT_ABSENT("text_absent");
            final String wireValue;
            Type(String wireValue) { this.wireValue = wireValue; }
        }
        public final Type type;
        public final String text;

        private ExpectedUiState(Type type, String text) {
            if (type == null || text == null || text.trim().isEmpty()
                    || text.trim().length() > UiNodeSnapshot.MAX_LABEL_CHARS) {
                throw new IllegalArgumentException("A precise expected UI label of 1 to 120 characters is required.");
            }
            this.type = type;
            this.text = text.trim().replaceAll("\\s+", " ");
        }
        public static ExpectedUiState textVisible(String text) {
            return new ExpectedUiState(Type.TEXT_VISIBLE, text);
        }
        public static ExpectedUiState textAbsent(String text) {
            return new ExpectedUiState(Type.TEXT_ABSENT, text);
        }
    }

    public static final class Request {
        public final String actionId;
        public final String taskId;
        public final ActionType actionType;
        public final String packageName;
        public final int windowId;
        public final long snapshotCapturedAtUptimeMillis;
        public final int snapshotAgeMs;
        public final String snapshotFingerprint;
        public final String targetFingerprint;
        public final String payloadSha256;
        public final String targetLabel;
        public final String targetViewId;
        public final String typedText;
        public final String postconditionType;
        public final String postconditionSha256;
        public final String expectedUiText;
        private final String saltHex;
        private final UiNodeSnapshot originalSnapshot;

        private Request(String taskId, ActionType actionType, UiNodeSnapshot snapshot,
                        UiNodeSnapshot.Node target, String targetLabel,
                        String targetViewId, String typedText,
                        ExpectedUiState expectedUiState, long nowUptimeMillis) {
            if (taskId == null || taskId.trim().isEmpty() || snapshot == null
                    || snapshot.windowId < 0 || snapshot.packageName.isEmpty()) {
                throw new IllegalArgumentException("A live task and identified foreground window are required.");
            }
            long age = nowUptimeMillis - snapshot.capturedAtUptimeMillis;
            if (age < 0 || age > UiTargetResolver.MAX_SNAPSHOT_AGE_MS) {
                throw new IllegalArgumentException("The UI snapshot is stale; observe the screen again.");
            }
            this.actionId = UUID.randomUUID().toString();
            this.taskId = taskId.trim();
            this.actionType = actionType;
            this.packageName = snapshot.packageName;
            this.windowId = snapshot.windowId;
            this.snapshotCapturedAtUptimeMillis = snapshot.capturedAtUptimeMillis;
            this.snapshotAgeMs = (int) age;
            this.saltHex = randomSalt();
            this.snapshotFingerprint = snapshotFingerprint(snapshot, saltHex);
            this.targetFingerprint = target == null
                    ? digest(saltHex + "|" + packageName + "|" + windowId + "|" + actionType.wireValue)
                    : targetFingerprint(snapshot, target, targetLabel, saltHex);
            this.targetLabel = targetLabel == null ? "" : targetLabel.trim();
            this.targetViewId = targetViewId == null ? "" : targetViewId.trim();
            this.typedText = typedText;
            this.payloadSha256 = typedText == null ? null : digest(saltHex + "|payload|" + typedText);
            this.originalSnapshot = snapshot;
            if (!snapshot.treeComplete) {
                throw new IllegalArgumentException("The accessibility tree is incomplete; observe the full screen again.");
            }
            if (actionType == ActionType.TYPE_TEXT) {
                if (expectedUiState != null) {
                    throw new IllegalArgumentException("Text entry uses its exact field-value readback postcondition.");
                }
                this.postconditionType = "field_value_matches";
                this.postconditionSha256 = this.payloadSha256;
                this.expectedUiText = null;
            } else {
                if (expectedUiState == null) {
                    throw new IllegalArgumentException("A semantic visible/absent UI postcondition is required.");
                }
                this.postconditionType = expectedUiState.type.wireValue;
                this.expectedUiText = expectedUiState.text;
                this.postconditionSha256 = digest(
                        saltHex + "|postcondition|" + expectedUiState.text.toLowerCase(Locale.ROOT));
            }
        }

        public String toCanonicalJson() {
            StringBuilder json = new StringBuilder(512);
            json.append("{\"action_id\":").append(quote(actionId));
            json.append(",\"action_type\":").append(quote(actionType.wireValue));
            json.append(",\"package_name\":").append(quote(packageName));
            if (payloadSha256 != null) {
                json.append(",\"payload_sha256\":").append(quote(payloadSha256));
            }
            json.append(",\"postcondition_sha256\":").append(quote(postconditionSha256));
            json.append(",\"postcondition_type\":").append(quote(postconditionType));
            json.append(",\"snapshot_age_ms\":").append(snapshotAgeMs);
            json.append(",\"snapshot_fingerprint\":").append(quote(snapshotFingerprint));
            json.append(",\"target_fingerprint\":").append(quote(targetFingerprint));
            json.append(",\"task_id\":").append(quote(taskId));
            json.append(",\"window_id\":").append(windowId).append('}');
            return json.toString();
        }

        public String transactionHash() {
            return digest("POST\n" + AUTHORIZE_PATH + "\n" + toCanonicalJson());
        }

        public boolean matchesReceipt(org.json.JSONObject receipt) {
            if (receipt == null || !"authorized".equals(receipt.optString("status"))) return false;
            if (!actionId.equals(receipt.optString("action_id"))
                    || !taskId.equals(receipt.optString("task_id"))
                    || !actionType.wireValue.equals(receipt.optString("action_type"))
                    || !packageName.equals(receipt.optString("package_name"))
                    || windowId != receipt.optInt("window_id", -1)
                    || !snapshotFingerprint.equals(receipt.optString("snapshot_fingerprint"))
                    || !targetFingerprint.equals(receipt.optString("target_fingerprint"))) return false;
            String returnedPayloadHash = receipt.isNull("payload_sha256")
                    ? null : receipt.optString("payload_sha256", null);
            if (payloadSha256 == null ? returnedPayloadHash != null
                    : !payloadSha256.equals(returnedPayloadHash)) return false;
            if (!postconditionType.equals(receipt.optString("postcondition_type"))
                    || !postconditionSha256.equals(receipt.optString("postcondition_sha256"))) return false;
            String auditEventId = receipt.optString("cipher_audit_event_id");
            String auditHash = receipt.optString("cipher_audit_hash");
            long auditSequence = receipt.optLong("cipher_audit_sequence", 0L);
            if (!(actionId + ".authorized").equals(auditEventId)
                    || auditSequence < 1L || !auditHash.matches("[0-9a-f]{64}")) return false;
            long expires = receipt.optLong("expires_at", 0L);
            return expires > System.currentTimeMillis() / 1000L;
        }

        public boolean matchesFreshSnapshot(UiNodeSnapshot snapshot) {
            return matchesFreshSnapshot(snapshot, android.os.SystemClock.uptimeMillis());
        }

        public boolean matchesFreshSnapshot(UiNodeSnapshot snapshot, long nowUptimeMillis) {
            if (snapshot == null || snapshot.windowId != windowId
                    || !packageName.equals(snapshot.packageName)) return false;
            long age = nowUptimeMillis - snapshot.capturedAtUptimeMillis;
            return age >= 0 && age <= UiTargetResolver.MAX_SNAPSHOT_AGE_MS
                    && snapshotFingerprint.equals(snapshotFingerprint(snapshot, saltHex));
        }

        public boolean hasObservedUiChange(UiNodeSnapshot snapshot) {
            return hasObservedUiChange(snapshot, android.os.SystemClock.uptimeMillis());
        }

        public boolean hasObservedUiChange(UiNodeSnapshot snapshot, long nowUptimeMillis) {
            if (snapshot == null) return false;
            long age = nowUptimeMillis - snapshot.capturedAtUptimeMillis;
            if (age < 0 || age > UiTargetResolver.MAX_SNAPSHOT_AGE_MS) return false;
            return !packageName.equals(snapshot.packageName)
                    || windowId != snapshot.windowId
                    || !snapshotFingerprint.equals(snapshotFingerprint(snapshot, saltHex));
        }

        public boolean matchesTarget(UiNodeSnapshot.Node node) {
            if (node == null) return false;
            return targetFingerprint.equals(targetFingerprint(
                    new UiNodeSnapshot(packageName, windowId,
                            snapshotCapturedAtUptimeMillis,
                            java.util.Collections.singletonList(node)),
                    node, targetLabel, saltHex));
        }

        /** A non-text action is successful only when the requested label transition occurs. */
        public boolean matchesPostcondition(UiNodeSnapshot after, long nowUptimeMillis) {
            if (after == null || expectedUiText == null
                    || !originalSnapshot.treeComplete || !after.treeComplete) return false;
            long age = nowUptimeMillis - after.capturedAtUptimeMillis;
            if (age < 0 || age > UiTargetResolver.MAX_SNAPSHOT_AGE_MS) return false;
            boolean beforeVisible = containsExactVisibleLabel(originalSnapshot, expectedUiText);
            boolean afterVisible = containsExactVisibleLabel(after, expectedUiText);
            if ("text_visible".equals(postconditionType)) return !beforeVisible && afterVisible;
            if ("text_absent".equals(postconditionType)) return beforeVisible && !afterVisible;
            return false;
        }

        private static boolean containsExactVisibleLabel(UiNodeSnapshot snapshot, String expected) {
            if (snapshot == null || expected == null || !snapshot.treeComplete) return false;
            String normalizedExpected = normalizeLabel(expected);
            for (UiNodeSnapshot.Node node : snapshot.nodes) {
                if (node != null && node.visibleToUser && node.right > node.left && node.bottom > node.top
                        && normalizedExpected.equals(normalizeLabel(node.label()))) return true;
            }
            return false;
        }

        private static String normalizeLabel(String value) {
            return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        }

        public boolean matchesTypedText(String actualText) {
            return payloadSha256 != null && actualText != null
                    && payloadSha256.equals(digest(saltHex + "|payload|" + actualText));
        }
    }

    private UiActionDispatcher() { }

    public static Request tap(String taskId, UiNodeSnapshot snapshot, String exactLabel,
                              ExpectedUiState expectedUiState) {
        return tap(taskId, snapshot, exactLabel, expectedUiState,
                android.os.SystemClock.uptimeMillis());
    }

    public static Request tap(String taskId, UiNodeSnapshot snapshot, String exactLabel,
                              ExpectedUiState expectedUiState, long nowUptimeMillis) {
        UiTargetResolver.Result match = UiTargetResolver.findUniqueClickable(
                snapshot, exactLabel, nowUptimeMillis);
        requireFound(match, "A unique clickable control");
        return new Request(taskId, ActionType.TAP, snapshot, match.node,
                exactLabel, null, null, expectedUiState, nowUptimeMillis);
    }

    public static Request typeText(String taskId, UiNodeSnapshot snapshot,
                                   String viewId, String exactText) {
        return typeText(taskId, snapshot, viewId, exactText, android.os.SystemClock.uptimeMillis());
    }

    public static Request typeText(String taskId, UiNodeSnapshot snapshot,
                                   String viewId, String exactText, long nowUptimeMillis) {
        if (exactText == null || exactText.trim().isEmpty() || exactText.length() > 1000) {
            throw new IllegalArgumentException("Text must contain 1 to 1000 characters.");
        }
        UiTargetResolver.Result match = UiTargetResolver.findUniqueEditableByViewId(
                snapshot, viewId, nowUptimeMillis);
        requireFound(match, "A unique non-password editable field with a view ID");
        return new Request(taskId, ActionType.TYPE_TEXT, snapshot, match.node,
                null, viewId, exactText, null, nowUptimeMillis);
    }

    public static Request scroll(String taskId, UiNodeSnapshot snapshot, boolean forward,
                                  ExpectedUiState expectedUiState) {
        return scroll(taskId, snapshot, forward, expectedUiState,
                android.os.SystemClock.uptimeMillis());
    }

    public static Request scroll(String taskId, UiNodeSnapshot snapshot, boolean forward,
                                 ExpectedUiState expectedUiState, long nowUptimeMillis) {
        UiTargetResolver.Result match = UiTargetResolver.findUniqueScrollable(snapshot, nowUptimeMillis);
        requireFound(match, "A unique scrollable control");
        return new Request(taskId, forward ? ActionType.SCROLL_FORWARD : ActionType.SCROLL_BACKWARD,
                snapshot, match.node, null, null, null, expectedUiState, nowUptimeMillis);
    }

    public static Request back(String taskId, UiNodeSnapshot snapshot, ExpectedUiState expectedUiState) {
        return back(taskId, snapshot, expectedUiState, android.os.SystemClock.uptimeMillis());
    }

    public static Request back(String taskId, UiNodeSnapshot snapshot, ExpectedUiState expectedUiState,
                               long nowUptimeMillis) {
        return new Request(taskId, ActionType.BACK, snapshot, null, null, null, null,
                expectedUiState, nowUptimeMillis);
    }

    public static String snapshotFingerprint(UiNodeSnapshot snapshot, String saltHex) {
        StringBuilder material = new StringBuilder();
        material.append(saltHex).append('|').append(snapshot.packageName)
                .append('|').append(snapshot.windowId)
                .append('|').append(snapshot.treeComplete);
        for (UiNodeSnapshot.Node node : snapshot.nodes) {
            if (node == null) continue;
            material.append('|').append(node.index).append(':').append(node.parentIndex)
                    .append(':').append(node.className).append(':').append(node.viewId)
                    .append(':').append(node.clickable).append(':').append(node.scrollable)
                    .append(':').append(node.editable).append(':').append(node.password)
                    .append(':').append(node.visibleToUser)
                    .append(':').append(node.left).append(',').append(node.top)
                    .append(',').append(node.right).append(',').append(node.bottom)
                    .append(':').append(digest(node.label()));
        }
        return digest(material.toString());
    }

    private static String targetFingerprint(UiNodeSnapshot snapshot, UiNodeSnapshot.Node node,
                                            String label, String saltHex) {
        String labelDigest = digest(label == null ? "" : label.trim().toLowerCase(Locale.ROOT));
        String material = saltHex + "|target|" + snapshot.packageName + "|" + snapshot.windowId
                + "|" + node.className + "|" + node.viewId + "|" + labelDigest
                + "|" + node.left + "," + node.top + "," + node.right + "," + node.bottom
                + "|" + node.clickable + "|" + node.scrollable + "|" + node.editable
                + "|" + node.password + "|" + node.visibleToUser;
        return digest(material);
    }

    private static void requireFound(UiTargetResolver.Result result, String description) {
        if (result.status != UiTargetResolver.Status.FOUND) {
            throw new IllegalArgumentException(description + " could not be resolved unambiguously: "
                    + result.status.name().toLowerCase(Locale.ROOT) + ".");
        }
    }

    private static String randomSalt() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder result = new StringBuilder(32);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        java.util.Arrays.fill(bytes, (byte) 0);
        return result.toString();
    }

    public static String digest(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(hash.length * 2);
            for (byte b : hash) output.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            java.util.Arrays.fill(hash, (byte) 0);
            return output.toString();
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 is unavailable.", failure);
        }
    }

    private static String quote(String value) {
        return JSONObject.quote(value);
    }
}
