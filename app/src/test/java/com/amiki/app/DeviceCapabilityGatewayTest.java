package com.amiki.app;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DeviceCapabilityGatewayTest {

    @Test
    public void exactLabelMatchIgnoresCaseAndOuterWhitespace() {
        List<DeviceCapabilityGateway.InstalledApp> apps = Collections.singletonList(
                new DeviceCapabilityGateway.InstalledApp("WhatsApp", "com.whatsapp")
        );

        List<DeviceCapabilityGateway.InstalledApp> matches =
                DeviceCapabilityGateway.findAppsByLabel(apps, "  whatsapp  ");

        assertEquals(1, matches.size());
        assertEquals("com.whatsapp", matches.get(0).packageName);
    }

    @Test
    public void partialLabelMatchIsRejected() {
        List<DeviceCapabilityGateway.InstalledApp> apps = Arrays.asList(
                new DeviceCapabilityGateway.InstalledApp("Google Chrome", "com.android.chrome"),
                new DeviceCapabilityGateway.InstalledApp("Chrome Beta", "com.chrome.beta")
        );

        assertTrue(DeviceCapabilityGateway.findAppsByLabel(apps, "Chrome").isEmpty());
    }

    @Test
    public void duplicateLabelsRemainAmbiguous() {
        List<DeviceCapabilityGateway.InstalledApp> apps = Arrays.asList(
                new DeviceCapabilityGateway.InstalledApp("Camera", "com.android.camera"),
                new DeviceCapabilityGateway.InstalledApp("Camera", "com.vendor.camera")
        );

        List<DeviceCapabilityGateway.InstalledApp> matches =
                DeviceCapabilityGateway.findAppsByLabel(apps, "Camera");

        assertEquals(2, matches.size());
    }

    @Test
    public void nullAndBlankLabelsDoNotMatch() {
        List<DeviceCapabilityGateway.InstalledApp> apps = Collections.singletonList(
                new DeviceCapabilityGateway.InstalledApp("Settings", "com.android.settings")
        );

        assertTrue(DeviceCapabilityGateway.findAppsByLabel(apps, null).isEmpty());
        assertTrue(DeviceCapabilityGateway.findAppsByLabel(apps, "   ").isEmpty());
    }
}
