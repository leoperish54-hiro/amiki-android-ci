package com.amiki.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Durable metadata-only task journal.
 *
 * User intent is retained in memory by the active runner but only its SHA-256
 * digest is persisted. Screen text, UI trees, typed values and recipient data
 * are never journalled. An in-flight action is never replayed after recovery.
 */
final class DeviceTaskJournal {
    enum State {
        RUNNING, INTERRUPTED, NEEDS_OWNER_INPUT, COMPLETED, FAILED, CANCELLED
    }

    interface Store {
        String read();
        void write(String value);
        void clear();
    }

    static final class Record {
        final String taskId;
        final String intentSha256;
        final int stepIndex;
        final State state;
        final String packageName;
        final String lastActionId;
        final String lastActionResult;
        final boolean actionInFlight;
        final long updatedAtMillis;

        Record(String taskId, String intentSha256, int stepIndex, State state,
               String packageName, String lastActionId, String lastActionResult,
               boolean actionInFlight, long updatedAtMillis) {
            this.taskId = taskId;
            this.intentSha256 = intentSha256;
            this.stepIndex = stepIndex;
            this.state = state;
            this.packageName = packageName == null ? "" : packageName;
            this.lastActionId = lastActionId == null ? "" : lastActionId;
            this.lastActionResult = lastActionResult == null ? "" : lastActionResult;
            this.actionInFlight = actionInFlight;
            this.updatedAtMillis = updatedAtMillis;
        }
    }

    private final Store store;
    private Record current;

    DeviceTaskJournal(Store store) {
        if (store == null) throw new IllegalArgumentException("A journal store is required.");
        this.store = store;
        this.current = decode(store.read());
    }

    synchronized Record current() {
        return current;
    }

    /** Start a task or explicitly resume a safely interrupted task with identical intent. */
    synchronized Record beginOrResume(String intent, long nowMillis) {
        if (intent == null || intent.trim().isEmpty() || intent.length() > 4000) {
            throw new IllegalArgumentException("Task intent must contain 1 to 4000 characters.");
        }
        String digest = UiActionDispatcher.digest(intent.trim());
        if (current != null && current.state == State.NEEDS_OWNER_INPUT
                && !current.intentSha256.equals(digest)) {
            throw new IllegalStateException(
                    "The previous device task requires explicit owner review before a new task can start.");
        }
        if (current != null && current.intentSha256.equals(digest)) {
            if (current.actionInFlight || "outcome_unknown".equals(current.lastActionResult)) {
                current = copy(current, current.stepIndex, State.NEEDS_OWNER_INPUT,
                        current.packageName, current.lastActionId, "outcome_unknown",
                        false, nowMillis);
                persist();
                return current;
            }
            if (current.state == State.INTERRUPTED || current.state == State.RUNNING) {
                current = copy(current, current.stepIndex, State.RUNNING,
                        current.packageName, current.lastActionId,
                        current.lastActionResult, false, nowMillis);
                persist();
                return current;
            }
            if (current.state == State.NEEDS_OWNER_INPUT) return current;
        }
        current = new Record(UUID.randomUUID().toString(), digest, 0, State.RUNNING,
                "", "", "", false, nowMillis);
        persist();
        return current;
    }

    synchronized void observed(String packageName, long nowMillis) {
        requireRunning();
        if (packageName == null || packageName.trim().isEmpty() || packageName.length() > 255) {
            throw new IllegalArgumentException("Observed package name is invalid.");
        }
        current = copy(current, current.stepIndex, current.state,
                packageName.trim(), current.lastActionId, current.lastActionResult,
                current.actionInFlight, nowMillis);
        persist();
    }

    synchronized void actionPending(String actionId, long nowMillis) {
        requireRunning();
        if (current.actionInFlight) throw new IllegalStateException("An action is already in flight.");
        if (actionId == null || actionId.trim().isEmpty() || actionId.length() > 128) {
            throw new IllegalArgumentException("Action ID is invalid.");
        }
        current = copy(current, current.stepIndex, State.RUNNING,
                current.packageName, actionId.trim(), "pending", true, nowMillis);
        persist();
    }

    synchronized void actionResult(String actionId, String result, long nowMillis) {
        if (current == null || !current.actionInFlight
                || !current.lastActionId.equals(actionId)) {
            throw new IllegalStateException("The outcome does not match the in-flight action.");
        }
        if (!"outcome_verified".equals(result)
                && !"outcome_unverified".equals(result)
                && !"stale_target_rejected".equals(result)
                && !"execution_failed".equals(result)) {
            throw new IllegalArgumentException("Unsupported action outcome.");
        }
        boolean verified = "outcome_verified".equals(result);
        current = copy(current, verified ? current.stepIndex + 1 : current.stepIndex,
                verified ? State.RUNNING : State.NEEDS_OWNER_INPUT,
                current.packageName, actionId, result, false, nowMillis);
        persist();
    }

    synchronized void needsOwnerInput(String safeReason, long nowMillis) {
        if (current == null || isTerminal(current.state)) return;
        String reason = safeReason == null ? "" : safeReason.trim();
        if (reason.length() > 240) reason = reason.substring(0, 240);
        current = copy(current, current.stepIndex, State.NEEDS_OWNER_INPUT,
                current.packageName, current.lastActionId,
                current.actionInFlight ? "outcome_unknown" : current.lastActionResult,
                false, nowMillis);
        persist();
    }

    /**
     * Explicit owner acknowledgement after inspecting an unresolved external
     * action. This never marks the old action successful; it only allows a
     * different task to be started without replaying the old action.
     */
    synchronized void acknowledgeOwnerReview(long nowMillis) {
        if (current == null || current.state != State.NEEDS_OWNER_INPUT) {
            throw new IllegalStateException("There is no unresolved task requiring owner review.");
        }
        current = copy(current, current.stepIndex, State.CANCELLED,
                current.packageName, current.lastActionId,
                "owner_reviewed_no_retry", false, nowMillis);
        persist();
    }

    static boolean hasPendingOwnerReview(Context context) {
        DeviceTaskJournal journal = new DeviceTaskJournal(new PreferencesStore(context));
        Record record = journal.current();
        return record != null && record.state == State.NEEDS_OWNER_INPUT;
    }

    static void acknowledgePendingOwnerReview(Context context) {
        DeviceTaskJournal journal = new DeviceTaskJournal(new PreferencesStore(context));
        journal.acknowledgeOwnerReview(System.currentTimeMillis());
    }

    synchronized void complete(long nowMillis) {
        requireRunning();
        if (current.actionInFlight) throw new IllegalStateException("Cannot complete while an action is in flight.");
        current = copy(current, current.stepIndex, State.COMPLETED, current.packageName,
                current.lastActionId, current.lastActionResult, false, nowMillis);
        persist();
    }

    synchronized void fail(long nowMillis) {
        if (current == null || isTerminal(current.state)) return;
        current = copy(current, current.stepIndex, State.FAILED, current.packageName,
                current.lastActionId, current.actionInFlight ? "outcome_unknown" : current.lastActionResult,
                false, nowMillis);
        persist();
    }

    synchronized void cancel(long nowMillis) {
        if (current == null || isTerminal(current.state)) return;
        boolean unresolvedAction = current.actionInFlight;
        current = copy(current, current.stepIndex,
                unresolvedAction ? State.NEEDS_OWNER_INPUT : State.CANCELLED,
                current.packageName, current.lastActionId,
                unresolvedAction ? "outcome_unknown" : current.lastActionResult,
                false, nowMillis);
        persist();
    }

    /** Call once when the process recovers; never infer success or replay an in-flight action. */
    synchronized Record recoverAfterProcessRestart(long nowMillis) {
        if (current == null || isTerminal(current.state)) return current;
        if (current.actionInFlight || "pending".equals(current.lastActionResult)) {
            current = copy(current, current.stepIndex, State.NEEDS_OWNER_INPUT,
                    current.packageName, current.lastActionId, "outcome_unknown", false, nowMillis);
        } else {
            current = copy(current, current.stepIndex, State.INTERRUPTED,
                    current.packageName, current.lastActionId, current.lastActionResult,
                    false, nowMillis);
        }
        persist();
        return current;
    }

    private void requireRunning() {
        if (current == null || current.state != State.RUNNING || current.actionInFlight) {
            throw new IllegalStateException("The task is not ready for another observation or action.");
        }
    }

    private void persist() {
        if (current == null) {
            store.clear();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("task_id", current.taskId);
            json.put("intent_sha256", current.intentSha256);
            json.put("step_index", current.stepIndex);
            json.put("state", current.state.name());
            json.put("package_name", current.packageName);
            json.put("last_action_id", current.lastActionId);
            json.put("last_action_result", current.lastActionResult);
            json.put("action_in_flight", current.actionInFlight);
            json.put("updated_at_ms", current.updatedAtMillis);
            store.write(json.toString());
        } catch (JSONException impossible) {
            throw new IllegalStateException("Task journal could not be serialized.", impossible);
        }
    }

    private static Record decode(String raw) {
        if (raw == null || raw.trim().isEmpty() || raw.length() > 8192) return null;
        try {
            JSONObject json = new JSONObject(raw);
            String taskId = json.getString("task_id");
            String hash = json.getString("intent_sha256");
            int index = json.getInt("step_index");
            State state = State.valueOf(json.getString("state"));
            String packageName = json.optString("package_name", "");
            String actionId = json.optString("last_action_id", "");
            String actionResult = json.optString("last_action_result", "");
            boolean inFlight = json.optBoolean("action_in_flight", false);
            long updated = json.optLong("updated_at_ms", 0L);
            if (!taskId.matches("[A-Fa-f0-9-]{36}") || !hash.matches("[0-9a-f]{64}")
                    || index < 0 || index > 16 || updated < 0
                    || packageName.length() > 255 || actionId.length() > 128
                    || actionResult.length() > 64) return null;
            return new Record(taskId, hash, index, state, packageName,
                    actionId, actionResult, inFlight, updated);
        } catch (Exception invalid) {
            return null;
        }
    }

    private static Record copy(Record source, int stepIndex, State state,
                               String packageName, String actionId, String actionResult,
                               boolean actionInFlight, long nowMillis) {
        return new Record(source.taskId, source.intentSha256, stepIndex, state,
                packageName, actionId, actionResult, actionInFlight, nowMillis);
    }

    private static boolean isTerminal(State state) {
        return state == State.COMPLETED || state == State.FAILED || state == State.CANCELLED;
    }

    private static final class PreferencesStore implements Store {
        private static final String PREFS = "amiki_device_task_journal";
        private static final String RECORD = "active_task";
        private final SharedPreferences preferences;

        PreferencesStore(Context context) {
            if (context == null) throw new IllegalArgumentException("A context is required.");
            preferences = context.getApplicationContext()
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        }

        @Override public String read() { return preferences.getString(RECORD, null); }
        @Override public void write(String value) {
            if (!preferences.edit().putString(RECORD, value).commit()) {
                throw new IllegalStateException("The task journal could not be persisted.");
            }
        }
        @Override public void clear() {
            if (!preferences.edit().remove(RECORD).commit()) {
                throw new IllegalStateException("The task journal could not be cleared.");
            }
        }
    }

    /** Small deterministic store used by local unit tests. */
    static final class MemoryStore implements Store {
        private String value;
        @Override public String read() { return value; }
        @Override public void write(String value) { this.value = value; }
        @Override public void clear() { value = null; }
    }
}
