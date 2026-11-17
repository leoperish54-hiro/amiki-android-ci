package com.amiki.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.util.UUID;

/**
 * Bounded, observation-driven Android UI task runner.
 *
 * Exactly one proposed action is considered at a time. Every UI side effect is
 * dispatched through AccessibilityOperatorService and its existing preview,
 * UOSA L2 biometric, fresh-node validation, and Cipher outcome-receipt path.
 */
final class DeviceTaskOrchestrator {
    interface Listener {
        void onStatus(String message);
        void onFinished(String message, boolean needsOwnerInput);
    }

    static final int MAX_ACTION_STEPS = 15;
    static final long MAX_RUNTIME_MS = 120_000L;

    private final Context context;
    private final String intent;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final DeviceTaskJournal journal;
    private final long startedAtUptime = SystemClock.uptimeMillis();

    private DeviceTaskController controller;
    private String taskId;
    private String previousActionResult;
    private String pendingActionId;
    private UiTaskPlanClient.Plan pendingPlan;
    private UiNodeSnapshot pendingPlanSource;
    private int pendingPlanStepIndex;
    private boolean planningInFlight;
    private boolean awaitingAction;
    private boolean finished;
    private boolean stopped;

    DeviceTaskOrchestrator(Context context, String intent, Listener listener) {
        if (context == null || intent == null || intent.trim().isEmpty() || intent.length() > 4000) {
            throw new IllegalArgumentException("A valid device task request is required.");
        }
        this.context = context.getApplicationContext();
        this.intent = intent.trim();
        this.listener = listener;
        this.journal = new DeviceTaskJournal(new PreferencesStore(this.context));
    }

    void start() {
        try {
            journal.recoverAfterProcessRestart(System.currentTimeMillis());
            DeviceTaskJournal.Record record = journal.beginOrResume(intent, System.currentTimeMillis());
            taskId = record.taskId;
            if (record.state == DeviceTaskJournal.State.NEEDS_OWNER_INPUT) {
                finishWithoutObservation(
                        "A previous action has an unknown or unresolved outcome. Inspect the phone before issuing another command.",
                        true);
                return;
            }
            if (record.state == DeviceTaskJournal.State.COMPLETED) {
                finishWithoutObservation("This task was already completed and will not be repeated.", false);
                return;
            }
            if (record.state == DeviceTaskJournal.State.CANCELLED
                    || record.state == DeviceTaskJournal.State.FAILED) {
                record = journal.beginOrResume(intent, System.currentTimeMillis());
                taskId = record.taskId;
            }
            controller = new DeviceTaskController(taskId);
            AccessibilityOperatorService.armObservation(controller, this);
            if (listener != null) listener.onStatus("Amiki is observing the current app and preparing one safe next step.");
            AccessibilityOperatorService.captureActiveWindowNow();
        } catch (Exception failure) {
            journal.needsOwnerInput("Task could not be safely started.", System.currentTimeMillis());
            finishWithoutObservation(
                    "The Android UI Operator is unavailable or the task could not be recovered. No action was performed.",
                    true);
        }
    }

    void onSnapshot(UiNodeSnapshot snapshot) {
        main.post(() -> handleSnapshot(snapshot));
    }

    private void handleSnapshot(UiNodeSnapshot snapshot) {
        if (finished || stopped || planningInFlight || awaitingAction) return;
        if (controller == null || controller.snapshot().state != DeviceTaskController.State.OBSERVING) return;
        if (isExpired()) {
            pauseForOwner("This task reached its two-minute execution limit. Review the phone before continuing.");
            return;
        }
        if (snapshot == null || !snapshot.treeComplete || snapshot.windowId < 0
                || snapshot.packageName.isEmpty()) {
            pauseForOwner("The current screen could not be observed completely. No action was performed.");
            return;
        }
        long age = SystemClock.uptimeMillis() - snapshot.capturedAtUptimeMillis;
        if (age < 0 || age > UiTargetResolver.MAX_SNAPSHOT_AGE_MS) return;

        if (pendingPlan != null && pendingPlanSource != null) {
            UiTaskPlanClient.Plan plan = pendingPlan;
            UiNodeSnapshot source = pendingPlanSource;
            int stepIndex = pendingPlanStepIndex;
            pendingPlan = null;
            pendingPlanSource = null;
            if (sameScreenState(source, snapshot)) {
                applyPlan(plan, snapshot, stepIndex);
                return;
            }
            // The app changed while Core was planning. Discard the stale
            // proposal and request a new one from the screen now on display.
        }

        try {
            journal.observed(snapshot.packageName, System.currentTimeMillis());
        } catch (RuntimeException failure) {
            pauseForOwner("The task journal could not record the current app state.");
            return;
        }

        DeviceTaskJournal.Record record = journal.current();
        if (record == null || record.stepIndex > MAX_ACTION_STEPS) {
            pauseForOwner("The task exceeded the maximum safe number of UI actions.");
            return;
        }

        planningInFlight = true;
        if (listener != null) listener.onStatus("Planning one next step from the current screen…");
        UiTaskPlanClient.requestNext(
                context, taskId, intent, record.stepIndex, snapshot,
                previousActionResult,
                (plan, error) -> main.post(() -> {
                    planningInFlight = false;
                    if (finished || stopped) return;
                    if (controller.snapshot().state != DeviceTaskController.State.OBSERVING) return;
                    if (plan == null) {
                        pauseForOwner(error == null
                                ? "The next UI step could not be validated."
                                : error);
                        return;
                    }
                    if (AccessibilityOperatorService.isCurrentSnapshot(snapshot)) {
                        applyPlan(plan, snapshot, record.stepIndex);
                    } else {
                        // Planning may take longer than the 1.5-second action
                        // freshness window. Reobserve and require identical
                        // structural screen evidence before using the proposal.
                        pendingPlan = plan;
                        pendingPlanSource = snapshot;
                        pendingPlanStepIndex = record.stepIndex;
                        AccessibilityOperatorService.captureActiveWindowNow();
                    }
                })
        );
    }

    private static boolean sameScreenState(UiNodeSnapshot first, UiNodeSnapshot second) {
        if (first == null || second == null) return false;
        String salt = "amiki-afdi-task-screen-comparison-v1";
        return UiActionDispatcher.snapshotFingerprint(first, salt)
                .equals(UiActionDispatcher.snapshotFingerprint(second, salt));
    }

    private void applyPlan(UiTaskPlanClient.Plan plan, UiNodeSnapshot source, int stepIndex) {
        if (!AccessibilityOperatorService.isCurrentSnapshot(source)) {
            AccessibilityOperatorService.captureActiveWindowNow();
            return;
        }
        if (plan.status == UiTaskPlanClient.Status.NEEDS_OWNER_INPUT) {
            pauseForOwner(plan.question);
            return;
        }
        if (plan.status == UiTaskPlanClient.Status.COMPLETED) {
            try {
                controller.beginVerification();
                controller.complete("Visible UI evidence confirmed: " + plan.evidenceSummary);
                journal.complete(System.currentTimeMillis());
                finishObservation();
                finish("The requested task is complete. Visible evidence: " + plan.evidenceSummary, false);
            } catch (RuntimeException failure) {
                pauseForOwner("The task could not be safely marked complete.");
            }
            return;
        }
        if (stepIndex >= MAX_ACTION_STEPS) {
            pauseForOwner("The task reached the 15-action safety limit before completion.");
            return;
        }

        try {
            String actionId;
            switch (plan.actionType) {
                case "tap":
                    actionId = AccessibilityOperatorService.requestTap(
                            plan.targetLabel, plan.expectedUiText,
                            "text_visible".equals(plan.expectedUiType));
                    break;
                case "type_text":
                    actionId = AccessibilityOperatorService.requestTypeText(
                            plan.targetViewId, plan.text);
                    break;
                case "scroll_forward":
                    actionId = AccessibilityOperatorService.requestScroll(
                            true, plan.expectedUiText,
                            "text_visible".equals(plan.expectedUiType));
                    break;
                case "scroll_backward":
                    actionId = AccessibilityOperatorService.requestScroll(
                            false, plan.expectedUiText,
                            "text_visible".equals(plan.expectedUiType));
                    break;
                case "back":
                    actionId = AccessibilityOperatorService.requestBack(
                            plan.expectedUiText,
                            "text_visible".equals(plan.expectedUiType));
                    break;
                default:
                    throw new IllegalArgumentException("Unsupported UI action.");
            }
            if (actionId == null || !actionId.equals(pendingActionId) || !awaitingAction) {
                throw new IllegalStateException("The action was not durably queued for owner approval.");
            }
            if (listener != null) {
                listener.onStatus("Action " + (stepIndex + 1) + " is waiting for your review and biometric approval.");
            }
        } catch (RuntimeException failure) {
            pauseForOwner("The proposed action could not be started safely. No further action will be attempted.");
        }
    }

    synchronized boolean onActionApprovalQueued(String actionId) {
        if (finished || stopped || awaitingAction || actionId == null || actionId.trim().isEmpty()) {
            return false;
        }
        try {
            journal.actionPending(actionId, System.currentTimeMillis());
            pendingActionId = actionId;
            awaitingAction = true;
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    void onActionOutcome(String actionId, String resultCode, boolean auditRecorded,
                         UiNodeSnapshot freshSnapshot) {
        main.post(() -> {
            if (finished || stopped || !awaitingAction || actionId == null
                    || !actionId.equals(pendingActionId)) return;
            awaitingAction = false;
            pendingActionId = null;

            if (!auditRecorded) {
                journal.needsOwnerInput("Cipher outcome receipt was not confirmed.", System.currentTimeMillis());
                pauseForOwner("The action may have been attempted, but its Cipher outcome receipt was not confirmed. Inspect the phone; Amiki will not retry it.");
                return;
            }
            try {
                journal.actionResult(actionId, resultCode, System.currentTimeMillis());
            } catch (RuntimeException failure) {
                pauseForOwner("The action outcome could not be matched to the durable task journal.");
                return;
            }
            if (!"outcome_verified".equals(resultCode)) {
                if (resultCode.equals("execution_failed")) {
                    finishObservation();
                    finish("Android rejected the action. No later step was attempted.", true);
                } else {
                    pauseForOwner("The action outcome was not verified. Inspect the current screen before continuing.");
                }
                return;
            }

            previousActionResult = resultCode;
            if (isExpired()) {
                pauseForOwner("The task reached its two-minute execution limit after a verified step.");
                return;
            }
            if (freshSnapshot != null && AccessibilityOperatorService.isCurrentSnapshot(freshSnapshot)) {
                handleSnapshot(freshSnapshot);
            } else {
                AccessibilityOperatorService.captureActiveWindowNow();
            }
        });
    }

    void onAuthorizationFailed(String actionId, boolean ownerCancelled, String safeReason) {
        main.post(() -> {
            if (finished || stopped || !awaitingAction || actionId == null
                    || !actionId.equals(pendingActionId)) return;
            awaitingAction = false;
            pendingActionId = null;
            try {
                journal.actionNotAuthorized(actionId, ownerCancelled, System.currentTimeMillis());
            } catch (RuntimeException failure) {
                journal.needsOwnerInput("The pre-execution authorization result could not be matched to the task journal.",
                        System.currentTimeMillis());
            }
            finishObservation();
            if (ownerCancelled) {
                finish("You cancelled the pending action. No further step was attempted.", false);
            } else {
                finish(safeReason == null || safeReason.trim().isEmpty()
                        ? "Owner authorization failed. No action was performed."
                        : safeReason, true);
            }
        });
    }

    void stopByOwner() {
        main.post(() -> {
            if (finished || stopped) return;
            stopped = true;
            if (controller != null) controller.stop();
            journal.cancel(System.currentTimeMillis());
            finishObservation();
            finish("The task was stopped. Any external side effect already accepted by another app may not be reversible.", false);
        });
    }

    void onServiceInterrupted() {
        main.post(() -> {
            if (finished || stopped) return;
            stopped = true;
            if (controller != null && !isTerminal(controller.snapshot().state)) controller.stop();
            journal.cancel(System.currentTimeMillis());
            finish("The Android UI Operator was interrupted. The task has been stopped and will not replay an action.", false);
        });
    }

    private static boolean isTerminal(DeviceTaskController.State state) {
        return state == DeviceTaskController.State.COMPLETED
                || state == DeviceTaskController.State.FAILED
                || state == DeviceTaskController.State.CANCELLED;
    }

    private boolean isExpired() {
        return SystemClock.uptimeMillis() - startedAtUptime > MAX_RUNTIME_MS;
    }

    private void pauseForOwner(String message) {
        if (finished || stopped) return;
        if (controller != null) {
            DeviceTaskController.Snapshot state = controller.snapshot();
            if (state.state != DeviceTaskController.State.NEEDS_OWNER_INPUT
                    && state.state != DeviceTaskController.State.COMPLETED
                    && state.state != DeviceTaskController.State.FAILED
                    && state.state != DeviceTaskController.State.CANCELLED) {
                try { controller.needsOwnerInput(message); } catch (RuntimeException ignored) { }
            }
        }
        journal.needsOwnerInput(message, System.currentTimeMillis());
        finishObservation();
        finish(message, true);
    }

    private void finishWithoutObservation(String message, boolean needsOwnerInput) {
        finish(message, needsOwnerInput);
    }

    private void finishObservation() {
        AccessibilityOperatorService.pauseObservation(this);
    }

    private void finish(String message, boolean needsOwnerInput) {
        if (finished) return;
        finished = true;
        if (listener != null) listener.onFinished(message, needsOwnerInput);
    }

    private static final class PreferencesStore implements DeviceTaskJournal.Store {
        private static final String PREFS = "amiki_device_task_journal";
        private static final String RECORD = "active_task";
        private final SharedPreferences preferences;

        PreferencesStore(Context context) {
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
}
