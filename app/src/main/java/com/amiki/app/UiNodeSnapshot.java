package com.amiki.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** In-memory, bounded description of the foreground accessibility tree. */
public final class UiNodeSnapshot {
    public static final int MAX_NODES = 80;
    public static final int MAX_LABEL_CHARS = 120;

    public static final class Node {
        public final int index;
        public final int parentIndex;
        public final String className;
        public final String text;
        public final String contentDescription;
        public final String viewId;
        public final boolean clickable;
        public final boolean scrollable;
        public final boolean editable;
        public final boolean password;
        public final boolean visibleToUser;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Node(int index, int parentIndex, String className, String text,
                    String contentDescription, String viewId, boolean clickable,
                    boolean scrollable, boolean editable, boolean password,
                    int left, int top, int right, int bottom) {
            this(index, parentIndex, className, text, contentDescription, viewId,
                    clickable, scrollable, editable, password,
                    left, top, right, bottom, true);
        }

        public Node(int index, int parentIndex, String className, String text,
                    String contentDescription, String viewId, boolean clickable,
                    boolean scrollable, boolean editable, boolean password,
                    int left, int top, int right, int bottom, boolean visibleToUser) {
            this.index = index;
            this.parentIndex = parentIndex;
            this.className = clean(className);
            this.text = password || editable ? "" : clean(text);
            this.contentDescription = password || editable ? "" : clean(contentDescription);
            this.viewId = clean(viewId);
            this.clickable = clickable;
            this.scrollable = scrollable;
            this.editable = editable;
            this.password = password;
            this.visibleToUser = visibleToUser;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public String label() {
            if (!text.isEmpty()) return text;
            return contentDescription;
        }
    }

    public final String packageName;
    public final int windowId;
    public final long capturedAtUptimeMillis;
    public final List<Node> nodes;
    /** False when the bounded traversal omitted any part of the accessibility tree. */
    public final boolean treeComplete;

    public UiNodeSnapshot(String packageName, long capturedAtUptimeMillis, List<Node> nodes) {
        this(packageName, -1, capturedAtUptimeMillis, nodes);
    }

    public UiNodeSnapshot(String packageName, int windowId,
                          long capturedAtUptimeMillis, List<Node> nodes) {
        this(packageName, windowId, capturedAtUptimeMillis, nodes, true);
    }

    public UiNodeSnapshot(String packageName, int windowId,
                          long capturedAtUptimeMillis, List<Node> nodes,
                          boolean treeComplete) {
        this.packageName = clean(packageName);
        this.windowId = windowId;
        this.capturedAtUptimeMillis = capturedAtUptimeMillis;
        this.treeComplete = treeComplete;
        List<Node> bounded = new ArrayList<>();
        if (nodes != null) {
            for (Node node : nodes) {
                if (node == null || bounded.size() >= MAX_NODES) break;
                bounded.add(node);
            }
        }
        this.nodes = Collections.unmodifiableList(bounded);
    }

    private static String clean(CharSequence value) {
        if (value == null) return "";
        String result = value.toString().trim();
        if (result.length() > MAX_LABEL_CHARS) {
            result = result.substring(0, MAX_LABEL_CHARS);
        }
        return result;
    }
}
