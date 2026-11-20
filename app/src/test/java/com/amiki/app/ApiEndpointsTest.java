package com.amiki.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class ApiEndpointsTest {
    private static final String GOAL_ID = "a31e7f0d-4a15-4e66-8f25-9e9d9db2a004";

    @Test
    public void uosaGeneralGrantEndpointsExposePersistentGrantLifecycle() {
        assertEquals(ApiEndpoints.API_V1 + "/security/general-grants/challenges",
                ApiEndpoints.UOSA_GENERAL_GRANT_CHALLENGES);
        assertEquals(ApiEndpoints.API_V1 + "/security/general-grants",
                ApiEndpoints.UOSA_GENERAL_GRANTS);
        assertEquals(ApiEndpoints.UOSA_GENERAL_GRANTS + "/current",
                ApiEndpoints.UOSA_GENERAL_GRANT_CURRENT);
    }

    @Test
    public void coreApiPathsSelectTheCorrectSessionClass() {
        assertTrue(ApiConnection.isPrivilegedApiPath("/api/v1/cipher/inspect"));
        assertTrue(ApiConnection.isPrivilegedApiPath("/api/v1/learning/review-queue"));
        assertTrue(ApiConnection.isPrivilegedApiPath("/api/v1/cloud/jobs"));
        assertFalse(ApiConnection.isPrivilegedApiPath("/api/v1/chat"));
        assertFalse(ApiConnection.isPrivilegedApiPath("/api/v1/agents/run"));
        assertFalse(ApiConnection.isPrivilegedApiPath("/api/v1/academic"));
        assertFalse(ApiConnection.isPrivilegedApiPath("/api/v1/device/state"));
    }

    @Test
    public void uosaRecoveryEndpointsUseOwnerSecurityRoutes() {
        assertEquals(ApiEndpoints.API_V1 + "/security/recovery/challenges", ApiEndpoints.UOSA_RECOVERY_CHALLENGES);
        assertEquals(ApiEndpoints.API_V1 + "/security/recovery/complete", ApiEndpoints.UOSA_RECOVERY_COMPLETE);
    }

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

    @Test
    public void ownerLearningIntakeRoutesUsePrivilegedLearningSession() {
        assertEquals(ApiEndpoints.API_V1 + "/learning/notes", ApiEndpoints.LEARNING_NOTES);
        assertEquals(ApiEndpoints.API_V1 + "/learning/consolidation/run",
                ApiEndpoints.LEARNING_CONSOLIDATION_RUN);
        assertTrue(ApiConnection.isPrivilegedApiPath("/api/v1/learning/notes"));
        assertTrue(ApiConnection.isPrivilegedApiPath("/api/v1/learning/consolidation/run"));
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
