package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AmikiVoiceEnginePolicyTest {
    @Test
    public void autoUsesOnDeviceProviderWhenPlatformReportsItAvailable() {
        assertEquals(AmikiVoiceEngine.Provider.ON_DEVICE,
                AmikiVoiceEngine.selectProvider(AmikiVoiceEngine.Mode.AUTO, 33, true));
    }

    @Test
    public void autoFallsBackToSystemProviderWhenOnDeviceRecognitionIsUnavailable() {
        assertEquals(AmikiVoiceEngine.Provider.SYSTEM,
                AmikiVoiceEngine.selectProvider(AmikiVoiceEngine.Mode.AUTO, 29, false));
        assertEquals(AmikiVoiceEngine.Provider.SYSTEM,
                AmikiVoiceEngine.selectProvider(AmikiVoiceEngine.Mode.AUTO, 33, false));
    }

    @Test
    public void explicitOnDeviceModeNeverSilentlyFallsBackToExternalProvider() {
        assertEquals(AmikiVoiceEngine.Provider.UNAVAILABLE,
                AmikiVoiceEngine.selectProvider(AmikiVoiceEngine.Mode.ON_DEVICE, 29, false));
        assertEquals(AmikiVoiceEngine.Provider.UNAVAILABLE,
                AmikiVoiceEngine.selectProvider(AmikiVoiceEngine.Mode.ON_DEVICE, 33, false));
    }

    @Test
    public void explicitSystemModeAlwaysUsesSystemProvider() {
        assertEquals(AmikiVoiceEngine.Provider.SYSTEM,
                AmikiVoiceEngine.selectProvider(AmikiVoiceEngine.Mode.SYSTEM, 29, false));
        assertEquals(AmikiVoiceEngine.Provider.SYSTEM,
                AmikiVoiceEngine.selectProvider(AmikiVoiceEngine.Mode.SYSTEM, 35, true));
    }

    @Test
    public void nullModeUsesAutoPolicy() {
        assertEquals(AmikiVoiceEngine.Provider.SYSTEM,
                AmikiVoiceEngine.selectProvider(null, 29, false));
    }
}
