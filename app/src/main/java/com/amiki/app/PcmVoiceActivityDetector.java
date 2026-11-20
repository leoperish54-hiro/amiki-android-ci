package com.amiki.app;

/**
 * Pure-Java port of Jarvis's adaptive energy VAD. It consumes mono PCM16 frames
 * (16 kHz recommended), adapts to the room noise floor only while speech is
 * absent, confirms onset, uses hysteresis on release and supports a raised
 * threshold while Amiki is speaking to reduce speaker echo. It never opens the
 * microphone itself.
 */
public final class PcmVoiceActivityDetector {
    public enum Event { NONE, SPEECH_STARTED, SPEECH_ENDED }

    public static final class Frame {
        public final Event event;
        public final double energy;
        public final double noiseFloor;
        public final double threshold;
        public final boolean speaking;
        private Frame(Event event, double energy, double noiseFloor,
                      double threshold, boolean speaking) {
            this.event = event;
            this.energy = energy;
            this.noiseFloor = noiseFloor;
            this.threshold = threshold;
            this.speaking = speaking;
        }
    }

    public static final long START_MS = 110L;
    public static final long SILENCE_MS = 650L;
    public static final long MAX_SEGMENT_MS = 20_000L;
    public static final double TRIGGER_OVER_FLOOR = 2.6;
    public static final double GUARD_BOOST = 2.4;
    public static final double RELEASE_RATIO = 0.6;

    private double noiseFloor = 0.01;
    private double smoothEnergy;
    private double threshold;
    private long onsetAt = -1L;
    private long speechStartedAt = -1L;
    private long lastLoudAt = -1L;
    private boolean speaking;
    private boolean guard;

    public synchronized void setGuard(boolean enabled) { guard = enabled; }
    public synchronized boolean isSpeaking() { return speaking; }

    public synchronized Frame process(short[] pcm, int count, long nowElapsedMs) {
        if (pcm == null || count <= 0 || count > pcm.length) return frame(Event.NONE);

        double sum = 0.0;
        for (int i = 0; i < count; i++) {
            double normalized = pcm[i] / 32768.0;
            sum += normalized * normalized;
        }
        double energy = Math.sqrt(sum / count);
        smoothEnergy += (energy - smoothEnergy) * 0.5;

        if (!speaking && onsetAt < 0L) {
            double adaptation = smoothEnergy > noiseFloor ? 0.0008 : 0.02;
            noiseFloor += (smoothEnergy - noiseFloor) * adaptation;
            noiseFloor = Math.max(noiseFloor, 0.0015);
        }

        threshold = noiseFloor * TRIGGER_OVER_FLOOR * (guard ? GUARD_BOOST : 1.0);
        double releaseThreshold = threshold * RELEASE_RATIO;
        Event event = Event.NONE;

        if (!speaking) {
            if (smoothEnergy > threshold) {
                if (onsetAt < 0L) {
                    onsetAt = nowElapsedMs;
                } else if (nowElapsedMs - onsetAt >= START_MS) {
                    speaking = true;
                    speechStartedAt = onsetAt;
                    lastLoudAt = nowElapsedMs;
                    event = Event.SPEECH_STARTED;
                }
            } else if (onsetAt >= 0L) {
                onsetAt = -1L; // transient, not speech
            }
        } else {
            if (smoothEnergy > releaseThreshold) lastLoudAt = nowElapsedMs;
            long quietFor = nowElapsedMs - lastLoudAt;
            long segmentFor = nowElapsedMs - speechStartedAt;
            if (quietFor >= SILENCE_MS || segmentFor >= MAX_SEGMENT_MS) {
                speaking = false;
                onsetAt = -1L;
                speechStartedAt = -1L;
                lastLoudAt = -1L;
                event = Event.SPEECH_ENDED;
            }
        }
        return frame(event);
    }

    public synchronized void reset() {
        onsetAt = -1L;
        speechStartedAt = -1L;
        lastLoudAt = -1L;
        speaking = false;
        smoothEnergy = 0.0;
    }

    private Frame frame(Event event) {
        return new Frame(event, smoothEnergy, noiseFloor, threshold, speaking);
    }
}
