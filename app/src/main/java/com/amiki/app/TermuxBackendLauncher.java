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
