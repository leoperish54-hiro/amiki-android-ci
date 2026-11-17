package com.amiki.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Sends one bounded observation to Core and validates its single-step proposal locally. */
final class UiTaskPlanClient {
    static final String ENDPOINT = ApiEndpoints.DEVICE_UI_TASK_NEXT;
    static final int MAX_RESPONSE_BYTES = 8192;

    enum Status { ACTION, COMPLETED, NEEDS_OWNER_INPUT }

    static final class Plan {
        final Status status;
        final String actionType;
        final String targetLabel;
        final String targetViewId;
        final String text;
        final String expectedUiType;
        final String expectedUiText;
        final String question;
        final String evidenceSummary;

        private Plan(Status status, String actionType, String targetLabel,
                     String targetViewId, String text, String expectedUiType,
                     String expectedUiText, String question, String evidenceSummary) {
            this.status = status;
            this.actionType = actionType;
            this.targetLabel = targetLabel;
            this.targetViewId = targetViewId;
            this.text = text;
            this.expectedUiType = expectedUiType;
            this.expectedUiText = expectedUiText;
            this.question = question;
            this.evidenceSummary = evidenceSummary;
        }
    }

    interface Callback {
        void onComplete(Plan plan, String safeError);
    }

    private UiTaskPlanClient() { }

    static void requestNext(Context context, String taskId, String intent, int stepIndex,
                            UiNodeSnapshot snapshot, String previousActionResult,
                            Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                if (snapshot == null || !snapshot.treeComplete
                        || snapshot.windowId < 0 || snapshot.packageName.isEmpty()) {
                    throw new IllegalArgumentException("A complete foreground accessibility snapshot is required.");
                }
                JSONObject body = buildRequest(taskId, intent, stepIndex, snapshot, previousActionResult);
                connection = ApiConnection.open(app, ENDPOINT);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(45000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = stream == null ? "" : readLimited(stream, MAX_RESPONSE_BYTES);
                if (status < 200 || status >= 300) {
                    throw new java.io.IOException("Core could not plan the next device step (HTTP " + status + ").");
                }
                JSONObject json = new JSONObject(response);
                Plan plan = parseAndValidate(json, taskId, stepIndex, snapshot,
                        android.os.SystemClock.uptimeMillis());
                if (callback != null) callback.onComplete(plan, null);
            } catch (Exception failure) {
                String message = failure instanceof java.io.IOException
                        ? failure.getMessage()
                        : "The next device step could not be validated. No action was performed.";
                if (callback != null) callback.onComplete(null, bounded(message, 220));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "Amiki-UI-Task-Plan").start();
    }

    static JSONObject buildRequest(String taskId, String intent, int stepIndex,
                                   UiNodeSnapshot snapshot, String previousActionResult)
            throws Exception {
        return buildRequest(taskId, intent, stepIndex, snapshot,
                previousActionResult, android.os.SystemClock.uptimeMillis());
    }

    static JSONObject buildRequest(String taskId, String intent, int stepIndex,
                                   UiNodeSnapshot snapshot, String previousActionResult,
                                   long nowUptimeMillis) throws Exception {
        if (taskId == null || taskId.trim().isEmpty() || taskId.length() > 128
                || intent == null || intent.trim().isEmpty() || intent.length() > 4000
                || stepIndex < 0 || stepIndex > 15) {
            throw new IllegalArgumentException("The UI task metadata is outside its limits.");
        }
        if (previousActionResult != null && !previousActionResult.matches(
                "outcome_verified|outcome_unverified|stale_target_rejected|execution_failed")) {
            throw new IllegalArgumentException("The previous action result is invalid.");
        }
        long age = nowUptimeMillis - snapshot.capturedAtUptimeMillis;
        if (age < 0 || age > UiTargetResolver.MAX_SNAPSHOT_AGE_MS) {
            throw new IllegalArgumentException("The foreground UI snapshot is stale.");
        }

        JSONObject body = new JSONObject();
        body.put("task_id", taskId);
        body.put("intent", intent);
        body.put("step_index", stepIndex);
        body.put("package_name", snapshot.packageName);
        body.put("window_id", snapshot.windowId);
        body.put("snapshot_age_ms", (int) age);
        body.put("tree_complete", snapshot.treeComplete);
        if (previousActionResult == null) body.put("previous_action_result", JSONObject.NULL);
        else body.put("previous_action_result", previousActionResult);

        JSONArray nodes = new JSONArray();
        for (UiNodeSnapshot.Node node : snapshot.nodes) {
            if (node == null) continue;
            boolean visible = node.visibleToUser
                    && node.right > node.left && node.bottom > node.top;
            JSONObject value = new JSONObject();
            // Do not transmit content or identifiers from hidden/off-screen
            // nodes. Keep only anonymous hierarchy position for parent checks.
            value.put("label", visible ? node.label() : "");
            value.put("view_id", visible ? node.viewId : "");
            value.put("class_name", visible ? node.className : "");
            value.put("index", node.index);
            value.put("parent_index", node.parentIndex);
            value.put("clickable", visible && node.clickable);
            value.put("editable", visible && node.editable);
            value.put("scrollable", visible && node.scrollable);
            value.put("password", visible && node.password);
            value.put("visible", visible);
            nodes.put(value);
        }
        if (nodes.length() == 0 || nodes.length() > UiNodeSnapshot.MAX_NODES) {
            throw new IllegalArgumentException("The accessibility snapshot has no usable nodes.");
        }
        body.put("nodes", nodes);
        return body;
    }

    static Plan parseAndValidate(JSONObject json, String taskId, int stepIndex,
                                 UiNodeSnapshot snapshot, long nowUptimeMillis)
            throws Exception {
        if (json == null || !taskId.equals(json.optString("task_id"))
                || stepIndex != json.optInt("step_index", -1)) {
            throw new IllegalArgumentException("Core's task response did not match the active task step.");
        }
        String status = json.optString("status");
        if ("needs_owner_input".equals(status)) {
            requireNullActionFields(json);
            requireEmptyEvidence(json);
            String question = requiredString(json, "question", 240);
            return new Plan(Status.NEEDS_OWNER_INPUT, null, null, null, null,
                    null, null, question, null);
        }
        if ("completed".equals(status)) {
            requireNullActionFields(json);
            if (!json.isNull("question")) {
                throw new IllegalArgumentException("A completion response cannot request owner input.");
            }
            String evidence = validateEvidence(json.optJSONArray("evidence_labels"), snapshot);
            return new Plan(Status.COMPLETED, null, null, null, null,
                    null, null, null, evidence);
        }
        if (!"action".equals(status)) throw new IllegalArgumentException("Core returned an unsupported task status.");

        String action = requiredString(json, "action_type", 32);
        String targetLabel = nullableString(json, "target_label", 120);
        String targetViewId = nullableString(json, "target_view_id", 255);
        String text = nullableString(json, "text", 1000);
        String expectedType = nullableString(json, "expected_ui_type", 32);
        String expectedText = nullableString(json, "expected_ui_text", 120);
        if (json.optJSONArray("evidence_labels") == null
                || json.optJSONArray("evidence_labels").length() != 0
                || !json.isNull("question")) {
            throw new IllegalArgumentException("Core returned a mixed action and completion response.");
        }

        if ("tap".equals(action)) {
            requireOnly(targetLabel, targetViewId, text, "tap");
            if (UiTargetResolver.findUniqueClickable(
                    snapshot, targetLabel, snapshot.capturedAtUptimeMillis).status
                    != UiTargetResolver.Status.FOUND) {
                throw new IllegalArgumentException("An exact unique visible tap target could not be resolved.");
            }
        } else if ("type_text".equals(action)) {
            if (targetViewId == null || targetViewId.isEmpty() || text == null || text.isEmpty()
                    || targetLabel != null || expectedType != null || expectedText != null) {
                throw new IllegalArgumentException("The text-entry proposal contains invalid fields.");
            }
            UiTargetResolver.Result editableTarget = UiTargetResolver.findUniqueEditableByViewId(
                    snapshot, targetViewId, snapshot.capturedAtUptimeMillis);
            if (editableTarget.status != UiTargetResolver.Status.FOUND) {
                throw new IllegalArgumentException("An exact unique non-password editable field could not be resolved.");
            }
            if (hasSensitiveTextEntryContext(snapshot, targetViewId)) {
                throw new IllegalArgumentException("Sensitive text-entry fields require direct owner interaction.");
            }
        } else if ("scroll_forward".equals(action) || "scroll_backward".equals(action)) {
            requireOnly(targetLabel, targetViewId, text, "scroll");
            if (UiTargetResolver.findUniqueScrollable(
                    snapshot, snapshot.capturedAtUptimeMillis).status
                    != UiTargetResolver.Status.FOUND) {
                throw new IllegalArgumentException("One unique visible scrollable container could not be resolved.");
            }
        } else if ("back".equals(action)) {
            requireOnly(targetLabel, targetViewId, text, "back");
        } else {
            throw new IllegalArgumentException("Core proposed an unsupported UI action.");
        }

        if (!"type_text".equals(action)) {
            if (!"text_visible".equals(expectedType) && !"text_absent".equals(expectedType)) {
                throw new IllegalArgumentException("A semantic postcondition is required.");
            }
            if (expectedText == null || expectedText.trim().isEmpty()) {
                throw new IllegalArgumentException("The UI postcondition label is missing.");
            }
            boolean beforeVisible = hasExactVisibleLabel(snapshot, expectedText);
            if ("text_visible".equals(expectedType) == beforeVisible) {
                throw new IllegalArgumentException("The expected UI label does not describe a new state transition.");
            }
        }
        return new Plan(Status.ACTION, action, targetLabel, targetViewId, text,
                expectedType, expectedText, null, null);
    }

    private static final String[] SENSITIVE_FIELD_TERMS = {
            "password", "passwd", "passcode", "pin", "otp", "one time",
            "verification code", "security code", "two factor", "2fa",
            "authenticator", "authentication", "cvv", "cvc", "card number",
            "credit card", "debit card", "payment", "checkout", "bank account",
            "account number", "iban", "routing number", "swift code"
    };

    private static boolean hasSensitiveTextEntryContext(
            UiNodeSnapshot snapshot, String targetViewId) {
        String idContext = targetViewId == null ? "" : targetViewId
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        if (containsSensitiveTerm(idContext)) return true;
        for (UiNodeSnapshot.Node node : snapshot.nodes) {
            if (node == null || !node.editable || !targetViewId.equals(node.viewId)) continue;
            String context = node.label();
            for (UiNodeSnapshot.Node parent : snapshot.nodes) {
                if (parent != null && parent.index == node.parentIndex) {
                    context = context + " " + parent.label();
                    break;
                }
            }
            return containsSensitiveTerm(context.toLowerCase(Locale.ROOT));
        }
        return false;
    }

    private static boolean containsSensitiveTerm(String value) {
        if (value == null || value.isEmpty()) return false;
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        for (String term : SENSITIVE_FIELD_TERMS) {
            if ((" " + normalized + " ").contains(" " + term + " ")) return true;
        }
        return false;
    }

    private static void requireEmptyEvidence(JSONObject json) {
        JSONArray evidence = json.optJSONArray("evidence_labels");
        if (evidence == null || evidence.length() != 0) {
            throw new IllegalArgumentException("This task response cannot contain completion evidence.");
        }
    }

    private static void requireNullActionFields(JSONObject json) {
        for (String key : new String[]{"action_type", "target_label", "target_view_id",
                "text", "expected_ui_type", "expected_ui_text"}) {
            if (!json.isNull(key)) throw new IllegalArgumentException("Core returned mixed task response fields.");
        }
    }

    private static String validateEvidence(JSONArray evidence, UiNodeSnapshot snapshot) throws Exception {
        if (evidence == null || evidence.length() < 1 || evidence.length() > 4) {
            throw new IllegalArgumentException("Task completion requires visible UI evidence.");
        }
        StringBuilder summary = new StringBuilder();
        for (int i = 0; i < evidence.length(); i++) {
            Object item = evidence.get(i);
            if (!(item instanceof String)) throw new IllegalArgumentException("Completion evidence must be text.");
            String label = ((String) item).trim();
            if (label.isEmpty() || label.length() > UiNodeSnapshot.MAX_LABEL_CHARS
                    || !hasExactVisibleLabel(snapshot, label)) {
                throw new IllegalArgumentException("A task completion label is not visible on the current screen.");
            }
            if (summary.length() > 0) summary.append("; ");
            summary.append(label);
        }
        return summary.toString();
    }

    private static boolean hasExactVisibleLabel(UiNodeSnapshot snapshot, String expected) {
        String normalized = normalize(expected);
        for (UiNodeSnapshot.Node node : snapshot.nodes) {
            if (node != null && node.visibleToUser && node.right > node.left && node.bottom > node.top
                    && normalized.equals(normalize(node.label()))) return true;
        }
        return false;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static void requireOnly(String label, String viewId, String text, String action) {
        if ("tap".equals(action)) {
            if (label == null || label.isEmpty() || viewId != null || text != null) {
                throw new IllegalArgumentException("The tap proposal contains invalid fields.");
            }
        } else if (label != null || viewId != null || text != null) {
            throw new IllegalArgumentException("The proposed action contains unrelated target or text fields.");
        }
    }

    private static String requiredString(JSONObject json, String key, int max) {
        String value = json.optString(key, "");
        if (value.trim().isEmpty() || value.length() > max) {
            throw new IllegalArgumentException("Core's " + key + " value is missing or invalid.");
        }
        return value.trim();
    }

    private static String nullableString(JSONObject json, String key, int max) {
        if (json.isNull(key)) return null;
        String value = json.optString(key, null);
        if (value == null || value.length() > max) {
            throw new IllegalArgumentException("Core's " + key + " value is invalid.");
        }
        return value;
    }

    private static String readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new java.io.IOException("Core planner response exceeded its size limit.");
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private static String bounded(String value, int max) {
        if (value == null) return "The UI task planner is unavailable.";
        String clean = value.replaceAll("[\\r\\n]+", " ").trim();
        return clean.length() <= max ? clean : clean.substring(0, max);
    }
}
