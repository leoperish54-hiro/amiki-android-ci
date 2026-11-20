package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AmikiWakeWordPhraseTest {
    @Test
    public void acceptsSpokenPhraseAndKwsTagForms() {
        assertTrue(AmikiWakeWordPhrase.isWakePhrase("hey amiki"));
        assertTrue(AmikiWakeWordPhrase.isWakePhrase("Hey_Amiki"));
        assertTrue(AmikiWakeWordPhrase.isWakePhrase("HEY-AMIKI"));
        assertTrue(AmikiWakeWordPhrase.isWakePhrase("heyamiki"));
    }

    @Test
    public void acceptsExactKeywordSpotterLabel() {
        assertTrue(AmikiWakeWordPhrase.isWakePhrase("hey_amiki"));
    }

    @Test
    public void rejectsNullAndEmptyResults() {
        assertFalse(AmikiWakeWordPhrase.isWakePhrase(null));
        assertFalse(AmikiWakeWordPhrase.isWakePhrase(""));
        assertFalse(AmikiWakeWordPhrase.isWakePhrase("   "));
    }

    @Test
    public void rejectsPartialOrAdditionalSpeech() {
        assertFalse(AmikiWakeWordPhrase.isWakePhrase("hey"));
        assertFalse(AmikiWakeWordPhrase.isWakePhrase("amiki"));
        assertFalse(AmikiWakeWordPhrase.isWakePhrase("hey amiki open settings"));
    }

    @Test
    public void rejectsOtherPhrases() {
        assertFalse(AmikiWakeWordPhrase.isWakePhrase("hello amiki"));
        assertFalse(AmikiWakeWordPhrase.isWakePhrase("hey akari"));
        assertFalse(AmikiWakeWordPhrase.isWakePhrase("play music"));
    }
}
