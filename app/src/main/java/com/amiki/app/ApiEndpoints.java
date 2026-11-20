package com.amiki.app;

import java.util.UUID;

/** Central local API endpoint registry shared by the active shell and legacy screens. */
final class ApiEndpoints {
    static final String BASE_URL = "http://127.0.0.1:8000";
    static final String API_V1 = BASE_URL + "/api/v1";

    static final String CHAT = API_V1 + "/chat";
    static final String CONVERSATIONS = API_V1 + "/conversations";
    static final String VOICE = API_V1 + "/voice";
    static final String CHAT_ATTACHMENTS = CHAT + "/attachments";
    static final String DIRECT_AGENT = API_V1 + "/agents/run";
    static final String HEALTH = API_V1 + "/health";
    static final String CLOUD = API_V1 + "/cloud";
    static final String CLOUD_STATUS = CLOUD + "/status";
    static final String CLOUD_JOBS = CLOUD + "/jobs";
    static final String CLOUD_GOALS = CLOUD + "/goals";
    static final String CLOUD_GOALS_PLAN = CLOUD_GOALS + "/plan";
    static final String CLOUD_AUTONOMY_STATUS = CLOUD + "/autonomy/status";
    static final String CLOUD_AUTONOMY_STOP = CLOUD + "/autonomy/stop";
    static final String CLOUD_AUTONOMY_RESUME = CLOUD + "/autonomy/resume";
    static final String CLOUD_MONITOR_STATUS = CLOUD + "/monitor/status";
    static final String CLOUD_MONITOR_EVENTS = CLOUD + "/monitor/events";
    static final String SETTINGS = API_V1 + "/settings";
    static final String MODEL_SETTINGS = SETTINGS + "/model";
    static final String MEMORY_SETTINGS = SETTINGS + "/memory";
    static final String MEMORY = API_V1 + "/memory";
    static final String LEARNING_REVIEW_QUEUE = API_V1 + "/learning/review-queue";
    static final String UOSA_SETUP_ENROLLMENT_CHALLENGES = API_V1 + "/security/setup/enrollment-challenges";
    static final String UOSA_SETUP_ENROLL = API_V1 + "/security/setup/enroll";
    static final String UOSA_ENROLLMENT_CHALLENGES = API_V1 + "/security/devices/enrollment-challenges";
    static final String UOSA_ENROLL_DEVICE = API_V1 + "/security/devices/enroll";
    static final String UOSA_CHALLENGES = API_V1 + "/security/challenges";
    static final String UOSA_SESSIONS = API_V1 + "/security/sessions";
    static final String UOSA_ELEVATION_CHALLENGES = API_V1 + "/security/elevation-challenges";
    static final String UOSA_ELEVATED_SESSIONS = API_V1 + "/security/elevated-sessions";
    static final String UOSA_RECOVERY_CHALLENGES = API_V1 + "/security/recovery/challenges";
    static final String UOSA_RECOVERY_COMPLETE = API_V1 + "/security/recovery/complete";
    static final String UOSA_GENERAL_GRANT_CHALLENGES = API_V1 + "/security/general-grants/challenges";
    static final String UOSA_GENERAL_GRANTS = API_V1 + "/security/general-grants";
    static final String UOSA_GENERAL_GRANT_CURRENT = UOSA_GENERAL_GRANTS + "/current";
    static final String CIPHER_STATUS = API_V1 + "/cipher/status";
    static final String CIPHER_INSPECT = API_V1 + "/cipher/inspect";
    static final String CAPABILITIES = API_V1 + "/capabilities";
    static final String SYSTEM_STATE = API_V1 + "/system-state";
    static final String DATABASE_STATUS = API_V1 + "/database/status";
    static final String DATABASE_SESSION = API_V1 + "/database/session";
    static final String DIAGNOSTICS = API_V1 + "/diagnostics";
    static final String OBSERVABILITY_EVENTS = API_V1 + "/observability/events";
    static final String OBSERVABILITY_DIAGNOSTICS = API_V1 + "/observability/diagnostics";
    static final String OBSERVABILITY_STATUS = API_V1 + "/observability/status";
    static final String OBSERVABILITY_SENTINEL_EVENTS = API_V1 + "/observability/sentinel/events";
    static final String ACADEMIC = API_V1 + "/academic";
    static final String WORKSPACE = API_V1 + "/workspace";
    static final String DEVICE_CAPABILITIES = API_V1 + "/device/capabilities";
    static final String DEVICE_STATE = API_V1 + "/device/state";
    static final String DEVICE_EXECUTE = API_V1 + "/device/execute";
    static final String DEVICE_PLAN = API_V1 + "/device/plan";
    static final String DEVICE_UI_ACTION_AUTHORIZE = API_V1 + "/device/ui-actions/authorize";
    static final String DEVICE_UI_ACTION_RESULT = API_V1 + "/device/ui-actions/result";
    static final String DEVICE_UI_TASK_NEXT = API_V1 + "/device/ui-tasks/next";

    static String cloudGoalAction(String goalId, String action) {
        if (!"pause".equals(action) && !"resume".equals(action) && !"cancel".equals(action)) {
            throw new IllegalArgumentException("Unsupported Cloud goal action.");
        }
        if (goalId == null) {
            throw new IllegalArgumentException("Invalid Cloud goal ID.");
        }
        String normalized;
        try {
            normalized = UUID.fromString(goalId).toString();
        } catch (IllegalArgumentException invalidId) {
            throw new IllegalArgumentException("Invalid Cloud goal ID.", invalidId);
        }
        if (!normalized.equalsIgnoreCase(goalId)) {
            throw new IllegalArgumentException("Invalid Cloud goal ID.");
        }
        return CLOUD_GOALS + "/" + normalized + "/" + action;
    }

    static String conversation(String conversationId) {
        return CONVERSATIONS + "/" + canonicalConversationId(conversationId);
    }

    static String conversationMessages(String conversationId) {
        return conversation(conversationId) + "/messages";
    }

    private static String canonicalConversationId(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Invalid conversation ID.");
        }
        final UUID parsed;
        try {
            parsed = UUID.fromString(value);
        } catch (IllegalArgumentException invalidId) {
            throw new IllegalArgumentException("Invalid conversation ID.", invalidId);
        }
        String normalized = parsed.toString();
        if (parsed.version() != 4 || !normalized.equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("Invalid conversation ID.");
        }
        return normalized;
    }

    private ApiEndpoints() {}

    static String workspace(String suffix) {
        if (suffix == null || suffix.isEmpty()) return WORKSPACE;
        return WORKSPACE + (suffix.startsWith("/") ? suffix : "/" + suffix);
    }
}
