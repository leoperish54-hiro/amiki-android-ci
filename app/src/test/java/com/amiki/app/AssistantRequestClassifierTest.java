package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AssistantRequestClassifierTest {
    @Test public void recognizesSupportedAndUnsupportedImperativeDeviceCommands() {
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("Amiki, open Spotify"));
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("Open Spotify and play Lucid Dreams"));
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("Search YouTube for physics"));
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("Send Joshua a message"));
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("Scroll down in Chrome"));
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("Please navigate to example.com"));
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("Could you please open WhatsApp and text Joshua?"));
        assertTrue(AssistantRequestClassifier.isDeviceActionRequest("I want you to search YouTube for physics"));
    }

    @Test public void leavesOrdinaryQuestionsAndConversationForCoreChat() {
        assertFalse(AssistantRequestClassifier.isDeviceActionRequest("What is software engineering?"));
        assertFalse(AssistantRequestClassifier.isDeviceActionRequest("How do I open Spotify?"));
        assertFalse(AssistantRequestClassifier.isDeviceActionRequest("Can you explain how YouTube search works?"));
        assertFalse(AssistantRequestClassifier.isDeviceActionRequest(null));
        assertFalse(AssistantRequestClassifier.isDeviceActionRequest("   "));
    }
}
