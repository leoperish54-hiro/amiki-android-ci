package com.amiki.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;

/** Single source of truth for launching Amiki's local Termux backend command. */
final class TermuxBackendLauncher {
    static final String PACKAGE_NAME = "com.termux";
    static final String SERVICE_CLASS = "com.termux.app.RunCommandService";
    static final String ACTION = "com.termux.RUN_COMMAND";
    static final String EXTRA_PATH = "com.termux.RUN_COMMAND_PATH";
    static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    static final String PYTHON_PATH = "/data/data/com.termux/files/usr/bin/python";
    static final String MODEL_BRIDGE_SCRIPT_PATH =
            "/data/data/com.termux/files/home/astra_core/scripts/amiki_wake_word_model_bridge.py";

    // These paths match the documented installation on Chimz's Termux environment.
    static final String SCRIPT_PATH =
            "/data/data/com.termux/files/home/astra_core/scripts/start_api.sh";
    static final String WORKING_DIRECTORY =
            "/data/data/com.termux/files/home/astra_core";

    private TermuxBackendLauncher() {}

    static Intent buildIntent() {
        Intent command = new Intent();
        command.setClassName(PACKAGE_NAME, SERVICE_CLASS);
        command.setAction(ACTION);
        command.putExtra(EXTRA_PATH, SCRIPT_PATH);
        command.putExtra(EXTRA_WORKDIR, WORKING_DIRECTORY);
        command.putExtra(EXTRA_BACKGROUND, true);
        return command;
    }

    static boolean startWakeWordModelBridge(Context context, String socketName) {
        try {
            Intent command = new Intent();
            command.setClassName(PACKAGE_NAME, SERVICE_CLASS);
            command.setAction(ACTION);
            command.putExtra(EXTRA_PATH, PYTHON_PATH);
            command.putExtra(EXTRA_WORKDIR, WORKING_DIRECTORY);
            command.putExtra(EXTRA_BACKGROUND, true);
            command.putExtra(EXTRA_ARGUMENTS,
                    new String[]{MODEL_BRIDGE_SCRIPT_PATH, "--token-socket", socketName});
            // The socket name is a locator, not a credential; the token is delivered
            // only after the Android peer authenticates Termux by its kernel UID.
            return context.startService(command) != null;
        } catch (RuntimeException deniedOrUnavailable) {
            return false;
        }
    }

    /** True only when Android reports that the explicit Termux service was started. */
    static boolean start(Context context) {
        try {
            ComponentName started = context.startService(buildIntent());
            return started != null;
        } catch (RuntimeException deniedOrUnavailable) {
            return false;
        }
    }
}
