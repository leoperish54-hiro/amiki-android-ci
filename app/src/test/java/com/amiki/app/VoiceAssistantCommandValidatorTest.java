package com.amiki.app;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VoiceAssistantCommandValidatorTest {
    @Test public void trimsRecognizedCommand() {
        assertEquals("Open Spotify", VoiceAssistantCommandValidator.validate("  Open Spotify  "));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNullCommand() {
        VoiceAssistantCommandValidator.validate(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsBlankCommand() {
        VoiceAssistantCommandValidator.validate("  ");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOversizedCommand() {
        VoiceAssistantCommandValidator.validate(
                new String(new char[VoiceAssistantCommandValidator.MAX_COMMAND_CHARS + 1]).replace('\0', 'x'));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsControlCharacters() {
        VoiceAssistantCommandValidator.validate("Open Spotify\u0000 now");
    }

    @Test public void preservesUnicodeAndNormalPunctuation() {
        assertEquals("Search YouTube for café", VoiceAssistantCommandValidator.validate("Search YouTube for café"));
    }
}
