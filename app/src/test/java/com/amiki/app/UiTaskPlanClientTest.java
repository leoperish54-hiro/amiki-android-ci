package com.amiki.app;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

public class UiTaskPlanClientTest {

    private UiNodeSnapshot screen() {
        UiNodeSnapshot.Node search = new UiNodeSnapshot.Node(
                0, -1, "android.widget.Button", "Search", "", "com.youtube:id/search",
                true, false, false, false, 0, 0, 120, 60, true);
        UiNodeSnapshot.Node home = new UiNodeSnapshot.Node(
                1, -1, "android.widget.TextView", "Home", "", "",
                false, false, false, false, 0, 70, 100, 120, true);
        UiNodeSnapshot.Node scroll = new UiNodeSnapshot.Node(
                2, -1, "android.widget.ScrollView", "", "", "",
                false, true, false, false, 0, 130, 300, 600, true);
        return new UiNodeSnapshot("com.google.android.youtube", 7, 1000L,
                Arrays.asList(search, home, scroll), true);
    }

    private JSONObject base(String status) throws Exception {
        JSONObject value = new JSONObject();
        value.put("task_id", "task-001");
        value.put("step_index", 2);
        value.put("status", status);
        value.put("action_type", JSONObject.NULL);
        value.put("target_label", JSONObject.NULL);
        value.put("target_view_id", JSONObject.NULL);
        value.put("text", JSONObject.NULL);
        value.put("expected_ui_type", JSONObject.NULL);
        value.put("expected_ui_text", JSONObject.NULL);
        value.put("evidence_labels", new JSONArray());
        value.put("question", JSONObject.NULL);
        return value;
    }

    private UiTaskPlanClient.Plan parse(JSONObject json) throws Exception {
        return UiTaskPlanClient.parseAndValidate(json, "task-001", 2, screen(), 5000L);
    }

    @Test
    public void acceptsOneExactTapWithAVisibleStateTransition() throws Exception {
        JSONObject json = base("action");
        json.put("action_type", "tap");
        json.put("target_label", "Search");
        json.put("expected_ui_type", "text_visible");
        json.put("expected_ui_text", "Search results");

        UiTaskPlanClient.Plan plan = parse(json);
        assertEquals(UiTaskPlanClient.Status.ACTION, plan.status);
        assertEquals("tap", plan.actionType);
        assertEquals("Search", plan.targetLabel);
    }

    @Test
    public void rejectsDuplicateVisibleTapTargets() throws Exception {
        UiNodeSnapshot original = screen();
        UiNodeSnapshot.Node duplicate = new UiNodeSnapshot.Node(
                3, -1, "android.widget.Button", "Search", "", "com.youtube:id/search_again",
                true, false, false, false, 0, 610, 120, 670, true);
        UiNodeSnapshot ambiguous = new UiNodeSnapshot(original.packageName, original.windowId,
                original.capturedAtUptimeMillis,
                Arrays.asList(original.nodes.get(0), original.nodes.get(1),
                        original.nodes.get(2), duplicate), true);

        JSONObject json = base("action");
        json.put("action_type", "tap");
        json.put("target_label", "Search");
        json.put("expected_ui_type", "text_visible");
        json.put("expected_ui_text", "Search results");
        try {
            UiTaskPlanClient.parseAndValidate(json, "task-001", 2, ambiguous, 1001L);
            fail("Ambiguous targets must be rejected.");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void rejectsPostconditionAlreadyVisibleBeforeTheAction() throws Exception {
        JSONObject json = base("action");
        json.put("action_type", "tap");
        json.put("target_label", "Search");
        json.put("expected_ui_type", "text_visible");
        json.put("expected_ui_text", "Home");
        try {
            parse(json);
            fail("An already-visible label is not a valid text-visible transition.");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void acceptsOnlyVisibleEvidenceForCompletion() throws Exception {
        JSONObject json = base("completed");
        json.put("evidence_labels", new JSONArray().put("Home"));
        UiTaskPlanClient.Plan plan = parse(json);
        assertEquals(UiTaskPlanClient.Status.COMPLETED, plan.status);
        assertEquals("Home", plan.evidenceSummary);
    }

    @Test
    public void rejectsCompletionWithoutVisibleEvidence() throws Exception {
        JSONObject json = base("completed");
        json.put("evidence_labels", new JSONArray().put("Playback active"));
        try {
            parse(json);
            fail("Completion without visible evidence must be rejected.");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void acceptsClarificationOnlyWithoutActionFields() throws Exception {
        JSONObject json = base("needs_owner_input");
        json.put("question", "Which account should I use?");
        UiTaskPlanClient.Plan plan = parse(json);
        assertEquals(UiTaskPlanClient.Status.NEEDS_OWNER_INPUT, plan.status);
        assertEquals("Which account should I use?", plan.question);
    }

    @Test
    public void rejectsPasswordFieldAsTextEntryTarget() throws Exception {
        UiNodeSnapshot.Node password = new UiNodeSnapshot.Node(
                0, -1, "android.widget.EditText", "", "", "com.example:id/password",
                false, false, true, true, 0, 0, 200, 60, true);
        UiNodeSnapshot secretScreen = new UiNodeSnapshot("com.example.app", 2, 1000L,
                Arrays.asList(password), true);
        JSONObject json = base("action");
        json.put("action_type", "type_text");
        json.put("target_view_id", "com.example:id/password");
        json.put("text", "not-a-real-secret");
        try {
            UiTaskPlanClient.parseAndValidate(json, "task-001", 2, secretScreen, 1001L);
            fail("Password fields must never be proposed for accessibility text entry.");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    @Test
    public void rejectsUnsupportedOrMixedActionShape() throws Exception {
        JSONObject json = base("action");
        json.put("action_type", "launch_shell");
        json.put("target_label", "Search");
        json.put("expected_ui_type", "text_visible");
        json.put("expected_ui_text", "Search results");
        try {
            parse(json);
            fail("Unrecognized action types must be rejected.");
        } catch (IllegalArgumentException expected) {
            assertNotNull(expected.getMessage());
        }
    }
}
