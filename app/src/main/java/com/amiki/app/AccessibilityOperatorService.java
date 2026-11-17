package com.amiki.app;

import android.accessibilityservice.AccessibilityService;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.graphics.Rect;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Opt-in observer for the universal Android UI operator.
 *
 * The service is intentionally inert until an owner arms one bounded task
 * from Amiki. It does not click, type, scroll, monitor in the background, or
 * transmit/persist screen content.
 */
public final class AccessibilityOperatorService extends AccessibilityService {
    private static volatile boolean observationArmed;
    private static volatile UiNodeSnapshot latestSnapshot;
    private static volatile String lastObservedPackage = "";
    private static volatile DeviceTaskController activeTaskController;
    private static final Object SESSION_LOCK = new Object();
    private static volatile long lastCaptureUptimeMillis;

    public static void armObservation(DeviceTaskController taskController) {
        if (taskController == null) throw new IllegalArgumentException("A task controller is required.");
        if (taskController.snapshot().state != DeviceTaskController.State.QUEUED) {
            throw new IllegalStateException("Only a newly queued task can arm observation.");
        }
        synchronized (SESSION_LOCK) {
            taskController.beginObservation();
            activeTaskController = taskController;
            latestSnapshot = null;
            lastObservedPackage = "";
            lastCaptureUptimeMillis = 0L;
            observationArmed = true;
        }
    }

    public static void stopObservation() {
        synchronized (SESSION_LOCK) {
            observationArmed = false;
            latestSnapshot = null;
            lastObservedPackage = "";
            lastCaptureUptimeMillis = 0L;
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

    public static boolean isObservationArmed() {
        return observationArmed;
    }

    public static UiNodeSnapshot latestSnapshot() {
        return latestSnapshot;
    }

    public static String lastObservedPackage() {
        return lastObservedPackage;
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
            UiNodeSnapshot snapshot = capture(root, rootPackage.toString());
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

    private UiNodeSnapshot capture(AccessibilityNodeInfo root, String packageName) {
        List<UiNodeSnapshot.Node> nodes = new ArrayList<>();
        Deque<PendingNode> queue = new ArrayDeque<>();
        queue.add(new PendingNode(root, -1, 0));
        try {
            while (!queue.isEmpty() && nodes.size() < UiNodeSnapshot.MAX_NODES) {
                PendingNode pending = queue.removeFirst();
                AccessibilityNodeInfo node = pending.node;
                try {
                    if (pending.depth > 12) continue;
                    boolean password = node.isPassword();
                    boolean editable = node.isEditable();
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    int index = nodes.size();
                    CharSequence className = node.getClassName();
                    CharSequence text = password || editable ? null : node.getText();
                    CharSequence description = password || editable ? null : node.getContentDescription();
                    String viewId = "";
                    try {
                        viewId = node.getViewIdResourceName();
                    } catch (RuntimeException ignored) {
                        // Some OEM accessibility providers do not expose resource IDs.
                    }
                    nodes.add(new UiNodeSnapshot.Node(
                            index, pending.parentIndex,
                            className == null ? "" : className.toString(),
                            text == null ? "" : text.toString(),
                            description == null ? "" : description.toString(),
                            viewId,
                            node.isClickable(),
                            node.isScrollable(),
                            editable,
                            password,
                            bounds.left, bounds.top, bounds.right, bounds.bottom
                    ));

                    int childCount = Math.min(node.getChildCount(), 20);
                    for (int i = 0; i < childCount
                            && queue.size() + nodes.size() < UiNodeSnapshot.MAX_NODES; i++) {
                        AccessibilityNodeInfo child = node.getChild(i);
                        if (child != null) {
                            queue.addLast(new PendingNode(child, index, pending.depth + 1));
                        }
                    }
                } finally {
                    if (node != root) node.recycle();
                }
            }
        } finally {
            while (!queue.isEmpty()) {
                AccessibilityNodeInfo remaining = queue.removeFirst().node;
                if (remaining != root) remaining.recycle();
            }
        }
        return new UiNodeSnapshot(packageName, SystemClock.uptimeMillis(), nodes);
    }

    @Override
    public void onInterrupt() {
        stopObservation();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Connection alone never arms observation.
    }

    @Override
    public void onDestroy() {
        stopObservation();
        super.onDestroy();
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
