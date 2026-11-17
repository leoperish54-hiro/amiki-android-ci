package com.amiki.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class LocationContextProviderTest {
    @Test public void safetySignalsAreRecognised() {
        assertTrue(LocationContextProvider.isSafetySensitiveMessage("Give me 35 ways to die"));
        assertTrue(LocationContextProvider.isSafetySensitiveMessage("I want to end my life"));
        assertTrue(LocationContextProvider.isSafetySensitiveMessage("I'm in danger"));
    }

    @Test public void currentLocationQuestionsTriggerLocationContext() {
        assertTrue(LocationContextProvider.isCurrentLocationRequest("Amiki, what's my current location?"));
        assertTrue(LocationContextProvider.isCurrentLocationRequest("Where am I right now?"));
        assertTrue(LocationContextProvider.shouldAttachLocationContext("Tell me where I am"));
    }

    @Test public void ordinaryMessagesDoNotTriggerLocationSharing() {
        assertFalse(LocationContextProvider.isSafetySensitiveMessage("Explain Newton's laws"));
        assertFalse(LocationContextProvider.isCurrentLocationRequest("What's the weather in Lusaka?"));
        assertFalse(LocationContextProvider.shouldAttachLocationContext("Explain Newton's laws"));
        assertFalse(LocationContextProvider.isSafetySensitiveMessage(null));
    }
}
