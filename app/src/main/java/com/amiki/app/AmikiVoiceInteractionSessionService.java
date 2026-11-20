package com.amiki.app;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** Creates the lightweight UI session requested by Android's voice framework. */
public final class AmikiVoiceInteractionSessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new AmikiVoiceInteractionSession(this);
    }
}
