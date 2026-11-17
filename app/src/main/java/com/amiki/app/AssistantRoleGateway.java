package com.amiki.app;

import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Public Android API wrapper for the optional default-assistant role (API 29+). */
public final class AssistantRoleGateway {
    private AssistantRoleGateway() {}

    public static boolean isAvailable(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 29) return false;
        RoleManager roles = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
        return roles != null && roles.isRoleAvailable(RoleManager.ROLE_ASSISTANT);
    }

    public static boolean isHeld(Context context) {
        if (!isAvailable(context)) return false;
        RoleManager roles = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
        return roles != null && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT);
    }

    public static Intent createRequestIntent(Context context) {
        if (!isAvailable(context)) {
            throw new IllegalStateException("The Android assistant role is unavailable on this device.");
        }
        RoleManager roles = (RoleManager) context.getSystemService(Context.ROLE_SERVICE);
        if (roles == null) {
            throw new IllegalStateException("Android role management is unavailable.");
        }
        return roles.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT);
    }

    public static String status(Context context) {
        if (Build.VERSION.SDK_INT < 29) return "Android 10 or newer is required for the assistant role.";
        if (!isAvailable(context)) return "This Android build does not expose the assistant role to Amiki.";
        return isHeld(context)
                ? "Amiki is the current default assistant."
                : "Amiki is not the current default assistant.";
    }
}
