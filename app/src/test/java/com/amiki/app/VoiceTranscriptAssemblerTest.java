package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class VoiceTranscriptAssemblerTest {
    @Test
    public void holdsIncompleteTrailingConjunctionAndJoinsNextSegment() {
        VoiceTranscriptAssembler assembler = new VoiceTranscriptAssembler();

        VoiceTranscriptAssembler.Update first = assembler.feed("open YouTube and", false, 1000L);
        assertFalse(first.isComplete());
        assertEquals(VoiceTranscriptAssembler.CONTINUE_MS, first.waitMillis);

        VoiceTranscriptAssembler.Update second = assembler.feed("play lucid dreams", false, 1250L);
        assertFalse(second.isComplete());
        assertEquals(VoiceTranscriptAssembler.SETTLE_MS, second.waitMillis);

        VoiceTranscriptAssembler.Update completed = assembler.flush();
        assertEquals("open YouTube and play lucid dreams", completed.completedText);
    }

    @Test
    public void explicitSentenceEndingCompletesImmediately() {
        VoiceTranscriptAssembler assembler = new VoiceTranscriptAssembler();
        VoiceTranscriptAssembler.Update update =
                assembler.feed("play Lucid Dreams by Juice WRLD.", false, 500L);

        assertTrue(update.isComplete());
        assertEquals("play Lucid Dreams by Juice WRLD.", update.completedText);
    }

    @Test
    public void stopOverrideIsNeverHeldBehindEndpointing() {
        VoiceTranscriptAssembler assembler = new VoiceTranscriptAssembler();
        VoiceTranscriptAssembler.Update update = assembler.feed("stop Amiki", false, 900L);

        assertTrue(update.isComplete());
        assertEquals("stop Amiki", update.completedText);
    }

    @Test
    public void maximumHoldBoundsAnUnfinishedThought() {
        VoiceTranscriptAssembler assembler = new VoiceTranscriptAssembler();
        VoiceTranscriptAssembler.Update first = assembler.feed("open the settings for", false, 1000L);
        assertFalse(first.isComplete());

        VoiceTranscriptAssembler.Update bounded = assembler.feed("the device", false, 7000L);
        assertTrue(bounded.isComplete());
        assertEquals("open the settings for the device", bounded.completedText);
    }

    @Test
    public void cancelDropsHeldTranscript() {
        VoiceTranscriptAssembler assembler = new VoiceTranscriptAssembler();
        assembler.feed("open YouTube and", false, 1000L);
        assembler.cancel();

        assertEquals("", assembler.heldText());
        assertFalse(assembler.flush().isComplete());
    }
}
