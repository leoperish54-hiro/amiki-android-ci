package com.amiki.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ApiEndpointsTest {
    private static final String GOAL_ID = "a31e7f0d-4a15-4e66-8f25-9e9d9db2a004";

    @Test
    public void cloudGoalPlannerUsesCorePlanningEndpoint() {
        assertEquals(ApiEndpoints.CLOUD + "/goals/plan", ApiEndpoints.CLOUD_GOALS_PLAN);
    }

    @Test
    public void cloudGoalLifecycleActionsUseCanonicalGoalId() {
        assertEquals(
                ApiEndpoints.CLOUD_GOALS + "/" + GOAL_ID + "/pause",
                ApiEndpoints.cloudGoalAction(GOAL_ID, "pause")
        );
        assertEquals(
                ApiEndpoints.CLOUD_GOALS + "/" + GOAL_ID + "/resume",
                ApiEndpoints.cloudGoalAction(GOAL_ID, "resume")
        );
        assertEquals(
                ApiEndpoints.CLOUD_GOALS + "/" + GOAL_ID + "/cancel",
                ApiEndpoints.cloudGoalAction(GOAL_ID, "cancel")
        );
    }

    @Test
    public void cloudGoalLifecycleActionsRejectUnknownActionsAndMalformedIds() {
        assertIllegalArgument(() -> ApiEndpoints.cloudGoalAction(GOAL_ID, "delete"));
        assertIllegalArgument(() -> ApiEndpoints.cloudGoalAction("not-a-uuid", "pause"));
        assertIllegalArgument(() -> ApiEndpoints.cloudGoalAction(
                "a31e7f0d-4a15-4e66-8f25-9e9d9db2a004-extra", "pause"));
    }

    private static void assertIllegalArgument(Runnable action) {
        try {
            action.run();
            fail("Expected an IllegalArgumentException.");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }

    @Test
    public void conversationEndpointsRequireCanonicalUuidV4() {
        assertEquals(
                ApiEndpoints.CONVERSATIONS + "/" + GOAL_ID,
                ApiEndpoints.conversation(GOAL_ID)
        );
        assertEquals(
                ApiEndpoints.CONVERSATIONS + "/" + GOAL_ID + "/messages",
                ApiEndpoints.conversationMessages(GOAL_ID)
        );
        assertIllegalArgument(() -> ApiEndpoints.conversation("not-a-uuid"));
        assertIllegalArgument(() -> ApiEndpoints.conversation(
                "a31e7f0d4a154e668f259e9d9db2a004"));
        assertIllegalArgument(() -> ApiEndpoints.conversation(
                "a31e7f0d-4a15-1e66-8f25-9e9d9db2a004"));
    }
}
