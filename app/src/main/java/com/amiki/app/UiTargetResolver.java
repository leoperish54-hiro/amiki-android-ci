package com.amiki.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Resolves only unique exact semantic matches from a fresh UI snapshot. */
public final class UiTargetResolver {
    public enum Status { FOUND, NOT_FOUND, AMBIGUOUS, INVALID_REQUEST }

    public static final class Result {
        public final Status status;
        public final UiNodeSnapshot.Node node;
        public final int matchCount;

        private Result(Status status, UiNodeSnapshot.Node node, int matchCount) {
            this.status = status;
            this.node = node;
            this.matchCount = matchCount;
        }
    }

    private UiTargetResolver() { }

    public static Result findUniqueClickable(UiNodeSnapshot snapshot, String requestedLabel) {
        if (snapshot == null || requestedLabel == null || requestedLabel.trim().isEmpty()) {
            return new Result(Status.INVALID_REQUEST, null, 0);
        }
        String target = normalize(requestedLabel);
        List<UiNodeSnapshot.Node> matches = new ArrayList<>();
        for (UiNodeSnapshot.Node node : snapshot.nodes) {
            if (node == null || !node.clickable || node.password || node.editable) continue;
            if (target.equals(normalize(node.label()))) matches.add(node);
        }
        if (matches.isEmpty()) return new Result(Status.NOT_FOUND, null, 0);
        if (matches.size() != 1) return new Result(Status.AMBIGUOUS, null, matches.size());
        return new Result(Status.FOUND, matches.get(0), 1);
    }

    private static String normalize(String value) {
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
