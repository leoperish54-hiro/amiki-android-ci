package com.amiki.app;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.service.voice.VoiceInteractionService;

/**
 * Android-native assistant host.
 *
 * Android keeps the selected VoiceInteractionService alive for the lifetime of
 * the active assistant role. Keep this component lightweight; the optional
 * software wake detector lives in AmikiWakeWordService and real interaction
 * work is delegated to AmikiVoiceInteractionSessionService.
 */
public final class AmikiVoiceInteractionService extends VoiceInteractionService {
    private static volatile AmikiVoiceInteractionService instance;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    public void onReady() {
        super.onReady();
        instance = this;
        AmikiWakeWordService.startIfEnabledFromVoiceInteraction(this);
    }

    @Override
    public void onShutdown() {
        if (instance == this) instance = null;
        super.onShutdown();
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    public static boolean isSystemActive() {
        return instance != null;
    }

    /**
     * Ask Android to display Amiki's assistant session. This is the supported
     * background-safe invocation path for a selected VoiceInteractionService;
     * it avoids launching an arbitrary Activity from an ordinary background
     * service after a hotword.
     */
    public static boolean showAssistantSession() {
        AmikiVoiceInteractionService service = instance;
        if (service == null) return false;
        service.mainHandler.post(() -> {
            if (instance != service) return;
            try {
                service.showSession(new Bundle(), 0);
            } catch (RuntimeException failure) {
                // The caller reports a safe status through the wake service.
            }
        });
        return true;
    }
}
