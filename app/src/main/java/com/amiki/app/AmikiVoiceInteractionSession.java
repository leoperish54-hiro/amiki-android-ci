package com.amiki.app;

import android.content.Intent;
import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;

/**
 * System-owned session surface. It routes both the Home gesture and local
 * "Hey Amiki" invocation into the same explicit Amiki assistant entry point.
 */
public final class AmikiVoiceInteractionSession extends VoiceInteractionSession {
    public AmikiVoiceInteractionSession(android.content.Context context) {
        super(context);
    }

    @Override
    public void onShow(Bundle args, int showFlags) {
        super.onShow(args, showFlags);
        Intent assist = new Intent(getContext(), AssistantEntryActivity.class);
        assist.setAction(Intent.ACTION_ASSIST);
        assist.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startVoiceActivity(assist);
        } catch (RuntimeException launchFailure) {
            // Android owns this session lifecycle. A rejected activity launch
            // must not crash the selected assistant service or leave a blank UI.
            android.util.Log.w("AmikiVoiceSession",
                    "Android rejected the assistant entry activity launch.", launchFailure);
            try {
                hide();
            } catch (RuntimeException ignored) {
                // The system may already have dismissed the session.
            }
        }
    }
}
