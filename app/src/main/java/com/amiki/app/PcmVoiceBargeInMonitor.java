package com.amiki.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.SystemClock;

/**
 * Local PCM microphone monitor used only while Amiki speaks.
 *
 * Jarvis keeps a local voice-activity detector alive so the owner can interrupt
 * an answer. This Android adapter uses VOICE_RECOGNITION AudioRecord, hardware
 * echo cancellation when available, the raised VAD guard threshold, and a short
 * playback-start guard. It never transcribes or stores microphone audio.
 */
public final class PcmVoiceBargeInMonitor {
    public interface Listener {
        void onSpeechStarted();
        void onUnavailable(String safeReason);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 320; // 20 ms at 16 kHz
    private static final long SELF_GUARD_MS = 350L;

    private final Object lock = new Object();
    private final Listener listener;
    private final PcmVoiceActivityDetector detector = new PcmVoiceActivityDetector();

    private volatile boolean running;
    private volatile boolean triggered;
    private long startedAtElapsed;
    private AudioRecord recorder;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private Thread worker;

    public PcmVoiceBargeInMonitor(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("A barge-in listener is required.");
        this.listener = listener;
    }

    public void start() {
        synchronized (lock) {
            if (running) return;
            int minimum = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) {
                throw new IllegalStateException("Android returned an invalid microphone buffer size.");
            }

            int bufferBytes = Math.max(minimum, FRAME_SAMPLES * 2 * 6);
            AudioRecord created = new AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes);
            if (created.getState() != AudioRecord.STATE_INITIALIZED) {
                created.release();
                throw new IllegalStateException("Android could not initialize the local interruption microphone.");
            }

            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    echoCanceler = AcousticEchoCanceler.create(created.getAudioSessionId());
                    if (echoCanceler != null) echoCanceler.setEnabled(true);
                }
            } catch (RuntimeException ignored) {
                echoCanceler = null;
            }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(created.getAudioSessionId());
                    if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
                }
            } catch (RuntimeException ignored) {
                noiseSuppressor = null;
            }

            detector.reset();
            detector.setGuard(true);
            created.startRecording();
            recorder = created;
            running = true;
            triggered = false;
            startedAtElapsed = SystemClock.elapsedRealtime();

            worker = new Thread(() -> readLoop(created), "Amiki-Local-Barge-In");
            worker.setDaemon(true);
            worker.start();
        }
    }

    public boolean isRunning() {
        return running;
    }

    public void stop() {
        AudioRecord current;
        synchronized (lock) {
            running = false;
            current = recorder;
        }
        if (current != null) {
            try {
                if (current.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) current.stop();
            } catch (RuntimeException ignored) { }
            releaseResources(current);
        }
        Thread thread = worker;
        if (thread != null && thread != Thread.currentThread()) thread.interrupt();
    }

    private void readLoop(AudioRecord source) {
        short[] frame = new short[FRAME_SAMPLES];
        try {
            while (running && !triggered) {
                int count = source.read(frame, 0, frame.length);
                if (count <= 0) {
                    if (count == AudioRecord.ERROR_INVALID_OPERATION
                            || count == AudioRecord.ERROR_BAD_VALUE
                            || count == AudioRecord.ERROR_DEAD_OBJECT) {
                        notifyUnavailable("Android stopped local interruption monitoring.");
                        break;
                    }
                    continue;
                }

                long now = SystemClock.elapsedRealtime();
                PcmVoiceActivityDetector.Frame result = detector.process(frame, count, now);
                // Wait through the first syllable of Amiki's own answer, then
                // allow a sustained human voice to interrupt once local VAD
                // plus platform echo cancellation confirm a speech onset.
                if (result.speaking && now - startedAtElapsed >= SELF_GUARD_MS) {
                    triggered = true;
                    running = false;
                    listener.onSpeechStarted();
                    break;
                }
            }
        } catch (SecurityException denied) {
            notifyUnavailable("Microphone permission was revoked.");
        } catch (RuntimeException failure) {
            notifyUnavailable("Local interruption monitoring is unavailable.");
        } finally {
            releaseResources(source);
            running = false;
        }
    }

    private void notifyUnavailable(String reason) {
        if (triggered) return;
        running = false;
        try { listener.onUnavailable(reason); } catch (RuntimeException ignored) { }
    }

    private void releaseResources(AudioRecord expected) {
        AcousticEchoCanceler aec = null;
        NoiseSuppressor ns = null;
        boolean releaseRecord = false;
        synchronized (lock) {
            if (recorder == expected) {
                recorder = null;
                aec = echoCanceler;
                echoCanceler = null;
                ns = noiseSuppressor;
                noiseSuppressor = null;
                releaseRecord = true;
            }
        }
        if (!releaseRecord) return;
        if (aec != null) {
            try { aec.setEnabled(false); } catch (RuntimeException ignored) { }
            try { aec.release(); } catch (RuntimeException ignored) { }
        }
        if (ns != null) {
            try { ns.setEnabled(false); } catch (RuntimeException ignored) { }
            try { ns.release(); } catch (RuntimeException ignored) { }
        }
        try { expected.release(); } catch (RuntimeException ignored) { }
    }
}
