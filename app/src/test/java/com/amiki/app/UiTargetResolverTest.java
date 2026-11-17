package com.amiki.app;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class UiTargetResolverTest {
    private UiNodeSnapshot.Node node(int index, String text, String description,
                                     boolean clickable, boolean editable, boolean password) {
        return new UiNodeSnapshot.Node(index, -1, "android.widget.Button",
                text, description, "", clickable, false, editable, password,
                0, 0, 100, 50);
    }

    @Test public void findsUniqueExactLabelIgnoringCaseAndWhitespace() {
        UiNodeSnapshot snapshot = new UiNodeSnapshot("com.example.app", 10,
                Arrays.asList(node(0, "  Search   YouTube ", "", true, false, false)));
        UiTargetResolver.Result result = UiTargetResolver.findUniqueClickable(snapshot, "search youtube");
        assertEquals(UiTargetResolver.Status.FOUND, result.status);
        assertEquals(0, result.node.index);
    }

    @Test public void rejectsAmbiguousExactLabels() {
        UiNodeSnapshot snapshot = new UiNodeSnapshot("com.example.app", 10,
                Arrays.asList(
                        node(0, "Play", "", true, false, false),
                        node(1, "", "Play", true, false, false)));
        UiTargetResolver.Result result = UiTargetResolver.findUniqueClickable(snapshot, "Play");
        assertEquals(UiTargetResolver.Status.AMBIGUOUS, result.status);
        assertEquals(2, result.matchCount);
        assertNull(result.node);
    }

    @Test public void ignoresNonClickableEditableAndPasswordNodes() {
        UiNodeSnapshot snapshot = new UiNodeSnapshot("com.example.app", 10,
                Arrays.asList(
                        node(0, "Search", "", false, false, false),
                        node(1, "Search", "", true, true, false),
                        node(2, "Search", "", true, false, true)));
        assertEquals(UiTargetResolver.Status.NOT_FOUND,
                UiTargetResolver.findUniqueClickable(snapshot, "Search").status);
    }

    @Test public void rejectsBlankRequestAndMissingSnapshot() {
        assertEquals(UiTargetResolver.Status.INVALID_REQUEST,
                UiTargetResolver.findUniqueClickable(null, "Open").status);
        UiNodeSnapshot snapshot = new UiNodeSnapshot("com.example.app", 10, null);
        assertEquals(UiTargetResolver.Status.INVALID_REQUEST,
                UiTargetResolver.findUniqueClickable(snapshot, " ").status);
    }

    @Test public void editableAndPasswordValuesAreNeverStored() {
        UiNodeSnapshot.Node editable = node(0, "secret text", "hint", true, true, false);
        UiNodeSnapshot.Node password = node(1, "secret password", "password", true, false, true);
        assertEquals("", editable.text);
        assertEquals("", password.text);
        assertEquals("", password.contentDescription);
    }
}
