package com.amiki.app;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Opt-in, task-bound Android UI operator. A UI side effect is possible only
 * after a fresh UOSA L2 authorization and a second local window/target check.
 * No screen text or typed text is persisted or sent to Cipher.
 */
public final class AccessibilityOperatorService extends AccessibilityService {
    private static volatile boolean observationArmed;
    private static volatile UiNodeSnapshot latestSnapshot;
    private static volatile String lastObservedPackage = "";
    private static volatile DeviceTaskController activeTaskController;
    private static volatile AccessibilityOperatorService serviceInstance;
    private static volatile PendingAction pendingAction;
    private static final Object SESSION_LOCK = new Object();
    private static volatile long lastCaptureUptimeMillis;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static void armObservation(DeviceTaskController taskController) {
        if (taskController == null) throw new IllegalArgumentException("A task controller is required.");
        if (taskController.snapshot().state != DeviceTaskController.State.QUEUED) {
            throw new IllegalStateException("Only a newly queued task can arm observation.");
        }
        synchronized (SESSION_LOCK) {
            if (serviceInstance == null) throw new IllegalStateException("Amiki UI Operator is not connected.");
            taskController.beginObservation();
            activeTaskController = taskController;
            latestSnapshot = null;
            lastObservedPackage = "";
            lastCaptureUptimeMillis = 0L;
            pendingAction = null;
            observationArmed = true;
        }
    }

    public static void stopObservation() {
        synchronized (SESSION_LOCK) {
            observationArmed = false;
            latestSnapshot = null;
            lastObservedPackage = "";
            lastCaptureUptimeMillis = 0L;
            pendingAction = null;
            DeviceTaskController task = activeTaskController;
            activeTaskController = null;
            if (task != null && !isTerminal(task.snapshot().state)) task.stop();
        }
    }

    private static boolean isTerminal(DeviceTaskController.State state) {
        return state == DeviceTaskController.State.COMPLETED
                || state == DeviceTaskController.State.FAILED
                || state == DeviceTaskController.State.CANCELLED;
    }

    public static DeviceTaskController.Snapshot activeTaskSnapshot() {
        DeviceTaskController task = activeTaskController;
        return task == null ? null : task.snapshot();
    }

    public static boolean isObservationArmed() { return observationArmed; }
    public static UiNodeSnapshot latestSnapshot() { return latestSnapshot; }
    public static String lastObservedPackage() { return lastObservedPackage; }

    /** Request a semantic tap. The owner sees the target and approves with fresh biometrics. */
    public static String requestTap(String exactLabel, String expectedUiLabel, boolean expectedVisible) {
        DeviceTaskController task = activeTaskController;
        UiNodeSnapshot snapshot = latestSnapshot;
        if (task == null) throw new IllegalStateException("No active owner-authorized device task.");
        UiActionDispatcher.ExpectedUiState expected = expectedVisible
                ? UiActionDispatcher.ExpectedUiState.textVisible(expectedUiLabel)
                : UiActionDispatcher.ExpectedUiState.textAbsent(expectedUiLabel);
        UiActionDispatcher.Request request = UiActionDispatcher.tap(
                task.snapshot().taskId, snapshot, exactLabel, expected);
        return startApproval(request, "Tap “" + exactLabel + "”; verify “" + expectedUiLabel
                + "” becomes " + (expectedVisible ? "visible" : "absent"));
    }

    /** Request text entry by exact resource ID; password/secure fields are rejected. */
    public static String requestTypeText(String viewId, String exactText) {
        DeviceTaskController task = activeTaskController;
        UiNodeSnapshot snapshot = latestSnapshot;
        if (task == null) throw new IllegalStateException("No active owner-authorized device task.");
        UiActionDispatcher.Request request = UiActionDispatcher.typeText(
                task.snapshot().taskId, snapshot, viewId, exactText);
        return startApproval(request, "Enter text in " + viewId);
    }

    /** Scroll only when the current foreground window has one unambiguous scrollable target. */
    public static String requestScroll(boolean forward, String expectedUiLabel, boolean expectedVisible) {
        DeviceTaskController task = activeTaskController;
        UiNodeSnapshot snapshot = latestSnapshot;
        if (task == null) throw new IllegalStateException("No active owner-authorized device task.");
        UiActionDispatcher.ExpectedUiState expected = expectedVisible
                ? UiActionDispatcher.ExpectedUiState.textVisible(expectedUiLabel)
                : UiActionDispatcher.ExpectedUiState.textAbsent(expectedUiLabel);
        UiActionDispatcher.Request request = UiActionDispatcher.scroll(
                task.snapshot().taskId, snapshot, forward, expected);
        return startApproval(request, (forward ? "Scroll forward" : "Scroll backward")
                + "; verify “" + expectedUiLabel + "” becomes "
                + (expectedVisible ? "visible" : "absent"));
    }

    public static String requestBack(String expectedUiLabel, boolean expectedVisible) {
        DeviceTaskController task = activeTaskController;
        UiNodeSnapshot snapshot = latestSnapshot;
        if (task == null) throw new IllegalStateException("No active owner-authorized device task.");
        UiActionDispatcher.ExpectedUiState expected = expectedVisible
                ? UiActionDispatcher.ExpectedUiState.textVisible(expectedUiLabel)
                : UiActionDispatcher.ExpectedUiState.textAbsent(expectedUiLabel);
        UiActionDispatcher.Request request = UiActionDispatcher.back(task.snapshot().taskId, snapshot, expected);
        return startApproval(request, "Navigate back; verify “" + expectedUiLabel + "” becomes "
                + (expectedVisible ? "visible" : "absent"));
    }

    private static String startApproval(UiActionDispatcher.Request request, String summary) {
        AccessibilityOperatorService service = serviceInstance;
        DeviceTaskController task = activeTaskController;
        if (service == null || !observationArmed || task == null
                || !request.taskId.equals(task.snapshot().taskId)) {
            throw new IllegalStateException("The bounded UI observation task is not active.");
        }
        synchronized (SESSION_LOCK) {
            if (pendingAction != null) throw new IllegalStateException("Another UI action is awaiting approval.");
            task.requestOwnerApproval(summary);
            pendingAction = new PendingAction(request, task);
        }
        Intent intent = new Intent(service, UiActionApprovalActivity.class);
        intent.putExtra(UiActionApprovalActivity.EXTRA_ACTION_ID, request.actionId);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            service.startActivity(intent);
            return request.actionId;
        } catch (RuntimeException failure) {
            synchronized (SESSION_LOCK) { pendingAction = null; }
            if (!isTerminal(task.snapshot().state)) task.fail("Could not open the owner action approval screen.");
            throw new IllegalStateException("Owner action approval could not be opened.", failure);
        }
    }

    static UiActionDispatcher.Request pendingRequest(String actionId) {
        PendingAction pending = pendingAction;
        return pending != null && pending.request.actionId.equals(actionId) ? pending.request : null;
    }

    static void onUiActionAuthorizationGranted(String actionId, JSONObject receipt) {
        PendingAction pending = pendingAction;
        AccessibilityOperatorService service = serviceInstance;
        if (pending == null || service == null || !pending.request.actionId.equals(actionId)) return;
        if (!pending.request.matchesReceipt(receipt)) {
            service.rejectPending(pending, "stale_target_rejected", "The Core authorization receipt did not match this action.");
            return;
        }
        try {
            pending.controller.ownerApproved();
        } catch (IllegalStateException invalidState) {
            service.rejectPending(pending, "stale_target_rejected", "The task was stopped before approval completed.");
            return;
        }
        // Let the approval activity finish so Android can restore the target app.
        service.mainHandler.postDelayed(() -> service.executePending(pending), 450L);
    }

    static void onUiActionAuthorizationFailed(String actionId, String safeReason) {
        PendingAction pending = pendingAction;
        AccessibilityOperatorService service = serviceInstance;
        if (pending == null || service == null || !pending.request.actionId.equals(actionId)) return;
        synchronized (SESSION_LOCK) { pendingAction = null; }
        DeviceTaskController.Snapshot state = pending.controller.snapshot();
        if (state.state == DeviceTaskController.State.AWAITING_OWNER_APPROVAL) {
            if (safeReason != null && safeReason.toLowerCase(Locale.ROOT).contains("cancel")) {
                pending.controller.ownerRejected();
            } else {
                pending.controller.ownerApprovalFailed(safeReason);
            }
        } else if (!isTerminal(state.state)) {
            pending.controller.fail(safeReason);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!observationArmed || event == null) return;
        int type = event.getEventType();
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        long now = SystemClock.uptimeMillis();
        if (now - lastCaptureUptimeMillis < 200L) return;
        CharSequence packageName = event.getPackageName();
        if (packageName == null || "com.amiki.app".contentEquals(packageName)) return;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        try {
            CharSequence rootPackage = root.getPackageName();
            if (rootPackage == null || "com.amiki.app".contentEquals(rootPackage)) return;
            UiNodeSnapshot snapshot = capture(root, rootPackage.toString(), root.getWindowId());
            synchronized (SESSION_LOCK) {
                if (observationArmed && activeTaskController != null) {
                    latestSnapshot = snapshot;
                    lastObservedPackage = snapshot.packageName;
                    lastCaptureUptimeMillis = SystemClock.uptimeMillis();
                }
            }
        } finally {
            root.recycle();
        }
    }

    private void executePending(PendingAction pending) {
        synchronized (SESSION_LOCK) {
            if (pendingAction != pending || !observationArmed) return;
        }
        DeviceTaskController.Snapshot taskState = pending.controller.snapshot();
        if (taskState.stopRequested || taskState.state != DeviceTaskController.State.EXECUTING) {
            rejectPending(pending, "stale_target_rejected", "The task was stopped before the action began.");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            rejectPending(pending, "stale_target_rejected", "No active accessibility window was available after approval.");
            return;
        }
        boolean accepted = false;
        try {
            CharSequence packageName = root.getPackageName();
            if (packageName == null || !pending.request.packageName.equals(packageName.toString())
                    || root.getWindowId() != pending.request.windowId) {
                rejectPending(pending, "stale_target_rejected", "Foreground app or window changed during approval.");
                return;
            }
            UiNodeSnapshot fresh = capture(root, packageName.toString(), root.getWindowId());
            if (!pending.request.matchesFreshSnapshot(fresh)) {
                rejectPending(pending, "stale_target_rejected", "The screen changed during approval; the action was not performed.");
                return;
            }
            switch (pending.request.actionType) {
                case TAP: {
                    UiTargetResolver.Result target = UiTargetResolver.findUniqueClickable(
                            fresh, pending.request.targetLabel, SystemClock.uptimeMillis());
                    if (target.status != UiTargetResolver.Status.FOUND
                            || !pending.request.matchesTarget(target.node)) {
                        rejectPending(pending, "stale_target_rejected", "The exact clickable target is no longer present.");
                        return;
                    }
                    AccessibilityNodeInfo node = findUniqueClickableNode(
                            root, pending.request.targetLabel, pending.request.windowId);
                    if (node == null || !pending.request.matchesTarget(toSnapshotNode(node))) {
                        if (node != null) node.recycle();
                        rejectPending(pending, "stale_target_rejected", "The fresh accessibility node did not match the approved target.");
                        return;
                    }
                    try { accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                    finally { node.recycle(); }
                    break;
                }
                case TYPE_TEXT: {
                    UiTargetResolver.Result target = UiTargetResolver.findUniqueEditableByViewId(
                            fresh, pending.request.targetViewId, SystemClock.uptimeMillis());
                    if (target.status != UiTargetResolver.Status.FOUND
                            || !pending.request.matchesTarget(target.node)) {
                        rejectPending(pending, "stale_target_rejected", "The approved editable field is no longer present.");
                        return;
                    }
                    AccessibilityNodeInfo node = findUniqueEditableNode(
                            root, pending.request.targetViewId, pending.request.windowId);
                    if (node == null || !pending.request.matchesTarget(toSnapshotNode(node))) {
                        if (node != null) node.recycle();
                        rejectPending(pending, "stale_target_rejected", "The fresh editable node did not match the approved field.");
                        return;
                    }
                    try {
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                        Bundle arguments = new Bundle();
                        arguments.putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                pending.request.typedText);
                        accepted = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
                    } finally { node.recycle(); }
                    break;
                }
                case SCROLL_FORWARD:
                case SCROLL_BACKWARD: {
                    UiTargetResolver.Result target = UiTargetResolver.findUniqueScrollable(
                            fresh, SystemClock.uptimeMillis());
                    if (target.status != UiTargetResolver.Status.FOUND
                            || !pending.request.matchesTarget(target.node)) {
                        rejectPending(pending, "stale_target_rejected", "The approved scroll container is no longer unique.");
                        return;
                    }
                    AccessibilityNodeInfo node = findUniqueScrollableNode(root, pending.request.windowId);
                    if (node == null || !pending.request.matchesTarget(toSnapshotNode(node))) {
                        if (node != null) node.recycle();
                        rejectPending(pending, "stale_target_rejected", "The fresh scroll container did not match the approved target.");
                        return;
                    }
                    int action = pending.request.actionType == UiActionDispatcher.ActionType.SCROLL_FORWARD
                            ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                            : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
                    try { accepted = node.performAction(action); }
                    finally { node.recycle(); }
                    break;
                }
                case BACK:
                    accepted = performGlobalAction(GLOBAL_ACTION_BACK);
                    break;
                default:
                    accepted = false;
            }
            if (!accepted) {
                rejectPending(pending, "execution_failed", "Android rejected the accessibility action.");
                return;
            }
            pending.controller.beginVerification();
        } catch (RuntimeException failure) {
            rejectPending(pending, "execution_failed", "The accessibility action failed safely.");
            return;
        } finally {
            root.recycle();
        }
        mainHandler.postDelayed(() -> verifyPending(pending), 450L);
    }

    private void verifyPending(PendingAction pending) {
        synchronized (SESSION_LOCK) {
            if (pendingAction != pending || !observationArmed) return;
        }
        String resultCode = "outcome_unverified";
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            try {
                if (pending.request.actionType == UiActionDispatcher.ActionType.TYPE_TEXT) {
                    AccessibilityNodeInfo field = findUniqueEditableNode(
                            root, pending.request.targetViewId, pending.request.windowId);
                    if (field != null) {
                        try {
                            CharSequence actual = field.getText();
                            if (actual != null && pending.request.matchesTypedText(actual.toString())) {
                                resultCode = "outcome_verified";
                            }
                        } finally { field.recycle(); }
                    }
                } else {
                    CharSequence packageName = root.getPackageName();
                    if (packageName != null) {
                        UiNodeSnapshot after = capture(root, packageName.toString(), root.getWindowId());
                        boolean matched = pending.request.matchesPostcondition(
                                after, SystemClock.uptimeMillis());
                        if (matched) resultCode = "outcome_verified";
                        synchronized (SESSION_LOCK) {
                            latestSnapshot = after;
                            lastObservedPackage = after.packageName;
                            lastCaptureUptimeMillis = SystemClock.uptimeMillis();
                        }
                    }
                }
            } finally { root.recycle(); }
        }
        recordOutcome(pending, resultCode,
                resultCode.equals("outcome_verified")
                        ? "A post-action UI state check matched the action's verification rule."
                        : "Android accepted the action, but its resulting UI state could not be verified.");
    }

    private void rejectPending(PendingAction pending, String resultCode, String safeDetail) {
        recordOutcome(pending, resultCode, safeDetail);
    }

    private void recordOutcome(PendingAction pending, String resultCode, String safeDetail) {
        synchronized (SESSION_LOCK) {
            if (pendingAction != pending) return;
            pendingAction = null;
        }
        UiActionNetworkClient.recordOutcome(this, pending.request, resultCode, (recorded, receiptDetail) ->
                mainHandler.post(() -> {
                    DeviceTaskController.Snapshot state = pending.controller.snapshot();
                    if (isTerminal(state.state)) return;
                    if (!recorded) {
                        pending.controller.needsOwnerInput("Action state: " + resultCode
                                + ". Cipher outcome receipt was not confirmed; stop and inspect the device.");
                    } else if ("outcome_verified".equals(resultCode)) {
                        if (state.state == DeviceTaskController.State.VERIFYING) {
                            pending.controller.verificationStepCompleted(safeDetail + " " + receiptDetail);
                        } else if (state.state == DeviceTaskController.State.EXECUTING) {
                            pending.controller.needsOwnerInput(safeDetail + " " + receiptDetail);
                        }
                    } else if ("stale_target_rejected".equals(resultCode)
                            || "outcome_unverified".equals(resultCode)) {
                        if (state.state == DeviceTaskController.State.VERIFYING
                                || state.state == DeviceTaskController.State.EXECUTING) {
                            pending.controller.needsOwnerInput(safeDetail);
                        }
                    } else {
                        pending.controller.fail(safeDetail);
                    }
                }));
    }

    private UiNodeSnapshot capture(AccessibilityNodeInfo root, String packageName, int windowId) {
        List<UiNodeSnapshot.Node> nodes = new ArrayList<>();
        Deque<PendingNode> queue = new ArrayDeque<>();
        queue.add(new PendingNode(root, -1, 0));
        boolean complete = true;
        try {
            while (!queue.isEmpty() && nodes.size() < UiNodeSnapshot.MAX_NODES) {
                PendingNode pending = queue.removeFirst();
                AccessibilityNodeInfo node = pending.node;
                try {
                    if (pending.depth > 12) {
                        complete = false;
                        continue;
                    }
                    nodes.add(toSnapshotNode(node, nodes.size(), pending.parentIndex));
                    int rawChildCount = node.getChildCount();
                    int childCount = Math.min(rawChildCount, 20);
                    if (rawChildCount > childCount) complete = false;
                    for (int i = 0; i < childCount; i++) {
                        if (queue.size() + nodes.size() >= UiNodeSnapshot.MAX_NODES) {
                            complete = false;
                            break;
                        }
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) {
                            queue.addLast(new PendingNode(child, nodes.size() - 1, pending.depth + 1));
                        } else {
                            complete = false;
                        }
                    }
                } finally {
                    if (node != root) node.recycle();
                }
            }
            if (!queue.isEmpty()) complete = false;
        } finally {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo remaining = queue.removeFirst().node;
                if (remaining != root) remaining.recycle();
            }
        }
        return new UiNodeSnapshot(packageName, windowId, SystemClock.uptimeMillis(), nodes, complete);
    }

    private static UiNodeSnapshot.Node toSnapshotNode(AccessibilityNodeInfo node) {
        return toSnapshotNode(node, 0, -1);
    }

    private static UiNodeSnapshot.Node toSnapshotNode(
            AccessibilityNodeInfo node, int index, int parentIndex) {
        boolean password = node.isPassword();
        boolean editable = node.isEditable();
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        CharSequence className = node.getClassName();
        CharSequence text = password || editable ? null : node.getText();
        CharSequence description = password || editable ? null : node.getContentDescription();
        String viewId = "";
        try { viewId = node.getViewIdResourceName(); }
        catch (RuntimeException ignored) { }
        return new UiNodeSnapshot.Node(
                index, parentIndex,
                className == null ? "" : className.toString(),
                text == null ? "" : text.toString(),
                description == null ? "" : description.toString(),
                viewId, node.isClickable(), node.isScrollable(), editable, password,
                bounds.left, bounds.top, bounds.right, bounds.bottom, node.isVisibleToUser());
    }

    private static AccessibilityNodeInfo findUniqueClickableNode(
            AccessibilityNodeInfo root, String exactLabel, int windowId) {
        if (exactLabel == null || exactLabel.trim().isEmpty()) return null;
        List<AccessibilityNodeInfo> matches;
        try { matches = root.findAccessibilityNodeInfosByText(exactLabel); }
        catch (RuntimeException failure) { return null; }
        AccessibilityNodeInfo selected = null;
        int count = 0;
        if (matches != null) {
            for (AccessibilityNodeInfo node : matches) {
                if (node == null) continue;
                CharSequence text = node.getText();
                CharSequence description = node.getContentDescription();
                boolean exact = equalsLabel(text, exactLabel) || equalsLabel(description, exactLabel);
                if (exact && node.isClickable() && !node.isEditable() && !node.isPassword()
                        && node.getWindowId() == windowId) {
                    count++;
                    if (count == 1) selected = node;
                    else node.recycle();
                } else node.recycle();
            }
        }
        if (count != 1) {
            if (selected != null) selected.recycle();
            return null;
        }
        return selected;
    }

    private static AccessibilityNodeInfo findUniqueEditableNode(
            AccessibilityNodeInfo root, String viewId, int windowId) {
        if (viewId == null || viewId.trim().isEmpty()) return null;
        List<AccessibilityNodeInfo> matches;
        try { matches = root.findAccessibilityNodeInfosByViewId(viewId); }
        catch (RuntimeException failure) { return null; }
        AccessibilityNodeInfo selected = null;
        int count = 0;
        if (matches != null) {
            for (AccessibilityNodeInfo node : matches) {
                if (node == null) continue;
                String actualId = "";
                try { actualId = node.getViewIdResourceName(); }
                catch (RuntimeException ignored) { }
                if (viewId.equals(actualId) && node.isEditable() && !node.isPassword()
                        && node.getWindowId() == windowId) {
                    count++;
                    if (count == 1) selected = node;
                    else node.recycle();
                } else node.recycle();
            }
        }
        if (count != 1) {
            if (selected != null) selected.recycle();
            return null;
        }
        return selected;
    }

    private static AccessibilityNodeInfo findUniqueScrollableNode(
            AccessibilityNodeInfo root, int windowId) {
        Deque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        queue.add(AccessibilityNodeInfo.obtain(root));
        AccessibilityNodeInfo selected = null;
        int count = 0;
        int visited = 0;
        try {
            while (!queue.isEmpty() && visited < UiNodeSnapshot.MAX_NODES) {
                AccessibilityNodeInfo node = queue.removeFirst();
                visited++;
                boolean keep = false;
                try {
                    if (node.isScrollable() && !node.isEditable() && !node.isPassword()
                            && node.getWindowId() == windowId) {
                        count++;
                        if (count == 1) {
                            selected = node;
                            keep = true;
                        }
                    }
                    if (visited < UiNodeSnapshot.MAX_NODES) {
                        int children = Math.min(node.getChildCount(), 20);
                        for (int i = 0; i < children && queue.size() + visited < UiNodeSnapshot.MAX_NODES; i++) {
                            AccessibilityNodeInfo child = node.getChild(i);
                            if (child != null) queue.addLast(child);
                        }
                    }
                } finally {
                    if (!keep) node.recycle();
                }
            }
        } finally {
            while (!queue.isEmpty()) queue.removeFirst().recycle();
        }
        if (count != 1) {
            if (selected != null) selected.recycle();
            return null;
        }
        return selected;
    }

    private static boolean equalsLabel(CharSequence actual, String requested) {
        if (actual == null) return false;
        return normalize(actual.toString()).equals(normalize(requested));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    @Override
    public void onInterrupt() { stopObservation(); }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        serviceInstance = this;
    }

    @Override
    public void onDestroy() {
        stopObservation();
        if (serviceInstance == this) serviceInstance = null;
        super.onDestroy();
    }

    private static final class PendingAction {
        final UiActionDispatcher.Request request;
        final DeviceTaskController controller;
        PendingAction(UiActionDispatcher.Request request, DeviceTaskController controller) {
            this.request = request;
            this.controller = controller;
        }
    }

    private static final class PendingNode {
        final AccessibilityNodeInfo node;
        final int parentIndex;
        final int depth;
        PendingNode(AccessibilityNodeInfo node, int parentIndex, int depth) {
            this.node = node;
            this.parentIndex = parentIndex;
            this.depth = depth;
        }
    }
}
