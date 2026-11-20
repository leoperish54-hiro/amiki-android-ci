package com.amiki.app;

import org.junit.Test;

import static org.junit.Assert.*;

public class PcmVoiceActivityDetectorTest {
    private short[] frame(int amplitude, int length) {
        short[] value = new short[length];
        java.util.Arrays.fill(value, (short) amplitude);
        return value;
    }

    @Test
    public void sustainedSpeechCrossesOnsetConfirmation() {
        PcmVoiceActivityDetector detector = new PcmVoiceActivityDetector();
        boolean started = false;
        for (int i = 0; i < 12; i++) {
            PcmVoiceActivityDetector.Event event =
                    detector.process(frame(5000, 320), 320, 1000L + i * 20L).event;
            started |= event == PcmVoiceActivityDetector.Event.SPEECH_STARTED;
        }
        assertTrue(started);
        assertTrue(detector.isSpeaking());
    }

    @Test
    public void shortTransientDoesNotBecomeSpeech() {
        PcmVoiceActivityDetector detector = new PcmVoiceActivityDetector();
        detector.process(frame(9000, 320), 320, 1000L);
        PcmVoiceActivityDetector.Event event = PcmVoiceActivityDetector.Event.NONE;
        for (int i = 0; i < 8; i++) {
            event = detector.process(frame(0, 320), 320, 1020L + i * 20L).event;
        }
        assertNotEquals(PcmVoiceActivityDetector.Event.SPEECH_STARTED, event);
        assertFalse(detector.isSpeaking());
    }

    @Test
    public void quietAfterSpeechEndsSegment() {
        PcmVoiceActivityDetector detector = new PcmVoiceActivityDetector();
        for (int i = 0; i < 12; i++) detector.process(frame(5000, 320), 320, 1000L + i * 20L);
        boolean ended = false;
        for (int i = 0; i < 45; i++) {
            PcmVoiceActivityDetector.Event event =
                    detector.process(frame(0, 320), 320, 1240L + i * 20L).event;
            ended |= event == PcmVoiceActivityDetector.Event.SPEECH_ENDED;
        }
        assertTrue(ended);
        assertFalse(detector.isSpeaking());
    }

    @Test
    public void guardRaisesSpeechThreshold() {
        PcmVoiceActivityDetector normal = new PcmVoiceActivityDetector();
        PcmVoiceActivityDetector guarded = new PcmVoiceActivityDetector();
        normal.process(frame(0, 320), 320, 1000L);
        guarded.process(frame(0, 320), 320, 1000L);
        guarded.setGuard(true);

        double normalThreshold = normal.process(frame(0, 320), 320, 1020L).threshold;
        double guardedThreshold = guarded.process(frame(0, 320), 320, 1020L).threshold;
        assertTrue(guardedThreshold > normalThreshold);
    }
}
