package com.amiki.app;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class AmikiOrbStateTest {
    @Test
    public void idleWhenNoActiveWorkAndHealthNotYetKnown() {
        assertEquals(AmikiOrbView.STATE_IDLE, AmikiOrbState.resolve(false, false, false, false, false));
    }

    @Test
    public void listeningTakesPriorityOverOtherStates() {
        assertEquals(AmikiOrbView.STATE_LISTENING, AmikiOrbState.resolve(true, true, true, true, false));
    }

    @Test
    public void thinkingTakesPriorityOverSpeechAndOfflineStatus() {
        assertEquals(AmikiOrbView.STATE_THINKING, AmikiOrbState.resolve(false, true, true, true, false));
    }

    @Test
    public void speakingAppearsWhenTextToSpeechIsActive() {
        assertEquals(AmikiOrbView.STATE_SPEAKING, AmikiOrbState.resolve(false, false, true, true, true));
    }

    @Test
    public void offlineOnlyAppearsAfterHealthCheckConfirmsFailure() {
        assertEquals(AmikiOrbView.STATE_OFFLINE, AmikiOrbState.resolve(false, false, false, true, false));
        assertEquals(AmikiOrbView.STATE_IDLE, AmikiOrbState.resolve(false, false, false, false, false));
    }

    @Test
    public void onlineIdleReturnsToReadyState() {
        assertEquals(AmikiOrbView.STATE_IDLE, AmikiOrbState.resolve(false, false, false, true, true));
    }
}
