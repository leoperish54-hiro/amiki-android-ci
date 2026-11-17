package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class DeviceCommandParserTest {

    @Test
    public void parsesBattery() {
        assertEquals(
                DeviceCommandParser.Action.BATTERY_STATE,
                DeviceCommandParser.parse("Amiki, check battery").action
        );
    }

    @Test
    public void parsesCallContact() {
        DeviceCommandParser.Command command =
                DeviceCommandParser.parse("Amiki, call Joshua");
        assertEquals(DeviceCommandParser.Action.CALL_CONTACT, command.action);
        assertEquals("Joshua", command.argument);
    }

    @Test
    public void parsesGenericInstalledAppLaunch() {
        DeviceCommandParser.Command command =
                DeviceCommandParser.parse("Amiki, open Spotify");
        assertEquals(DeviceCommandParser.Action.OPEN_APP, command.action);
        assertEquals("Spotify", command.argument);
    }

    @Test
    public void parsesLaunchVerbAndAppLabel() {
        DeviceCommandParser.Command command =
                DeviceCommandParser.parse("launch WhatsApp");
        assertEquals(DeviceCommandParser.Action.OPEN_APP, command.action);
        assertEquals("WhatsApp", command.argument);
    }

    @Test
    public void leavesCompoundOpenAndPlayRequestForTheCorePlanner() {
        assertNull(DeviceCommandParser.parse("open Spotify and play Lucid Dreams"));
    }

    @Test
    public void leavesUrlsForTheCorePlanner() {
        assertNull(DeviceCommandParser.parse("open https://example.com"));
    }

    @Test
    public void parsesAlarm24Hour() {
        DeviceCommandParser.Command command =
                DeviceCommandParser.parse("set an alarm for 18:30 to study MAT 1100");
        assertEquals(DeviceCommandParser.Action.SET_ALARM, command.action);
        assertEquals(18, command.hour);
        assertEquals(30, command.minute);
        assertEquals("study MAT 1100", command.message);
    }

    @Test
    public void parsesAlarm12Hour() {
        DeviceCommandParser.Command command =
                DeviceCommandParser.parse("set an alarm for 6 PM");
        assertEquals(18, command.hour);
        assertEquals(0, command.minute);
    }

    @Test
    public void rejectsInvalidAlarm() {
        assertNull(DeviceCommandParser.parse("set an alarm for 25:00"));
        assertNull(DeviceCommandParser.parse("set an alarm for 18:75"));
        assertNull(DeviceCommandParser.parse("set an alarm for 13 PM"));
    }

    @Test
    public void rejectsAmbiguousDeviceCommand() {
        assertNull(DeviceCommandParser.parse("do something on my phone"));
    }
}
