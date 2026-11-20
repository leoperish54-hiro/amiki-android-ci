package com.amiki.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.KeywordSpotter;
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;

import java.io.File;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Amiki-owned streaming keyword detector backed by the sherpa-onnx CPU KWS
 * runtime. It listens only while the owner-enabled foreground service is
 * active; all captured samples are processed locally and never uploaded.
 */
final class AmikiWakeWordEngine {
    interface Listener {
        void onWakeWordDetected();
        void onEngineError(Exception error);
    }

    private static final int SAMPLE_RATE = 16000;
    private static final int FRAME_SAMPLES = 1600; // 100 ms chunks
    private static final String WAKE_PHRASE = "hey amiki";

    private final KeywordSpotter spotter;
    private final OnlineStream stream;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean resourcesReleased = new AtomicBoolean(false);
    private volatile AudioRecord audioRecord;
    private volatile Thread captureThread;

    private AmikiWakeWordEngine(KeywordSpotter spotter, OnlineStream stream) {
        this.spotter = spotter;
        this.stream = stream;
    }

    static AmikiWakeWordEngine create(Context context) throws Exception {
        if (context == null) throw new IllegalArgumentException("A context is required.");
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission is required.");
        }
        File directory = AmikiWakeWordModel.modelDirectory(context);
        if (!AmikiWakeWordModel.isInstalled(context)) {
            throw new IllegalStateException("The verified Hey Amiki model is not installed.");
        }

        String root = directory.getAbsolutePath() + File.separator;
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(root + "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
                .setDecoder(root + "decoder-epoch-12-avg-2-chunk-16-left-64.onnx")
                .setJoiner(root + "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
                .build();
        OnlineModelConfig model = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(root + "tokens.txt")
                .setNumThreads(1)
                .setDebug(false)
                .setProvider("cpu")
                .setModelType("zipformer2")
                .setModelingUnit("bpe")
                .setBpeVocab(root + "bpe.model")
                .build();
        KeywordSpotterConfig config = KeywordSpotterConfig.builder()
                .setFeatureConfig(FeatureConfig.builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setFeatureDim(80)
                        .build())
                .setOnlineModelConfig(model)
                .setMaxActivePaths(4)
                .setKeywordsScore(1.0f)
                .setKeywordsThreshold(0.25f)
                .setNumTrailingBlanks(1)
                .build();

        KeywordSpotter spotter = new KeywordSpotter(config);
        try {
            OnlineStream stream = spotter.createStream(WAKE_PHRASE);
            if (stream == null || stream.getPtr() == 0L) {
                throw new IllegalStateException("The local wake phrase could not be initialized.");
            }
            return new AmikiWakeWordEngine(spotter, stream);
        } catch (RuntimeException failure) {
            spotter.release();
            throw failure;
        }
    }

    boolean isListening() {
        AudioRecord recorder = audioRecord;
        return running.get() && recorder != null
                && recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING;
    }

    void start(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("A wake-word listener is required.");
        if (closed.get()) throw new IllegalStateException("The wake-word engine is already closed.");
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("The wake-word engine is already running.");
        }

        int minimumBytes = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minimumBytes <= 0) {
            running.set(false);
            throw new IllegalStateException("Android could not determine a microphone buffer size.");
        }

        int bufferBytes = Math.max(minimumBytes, FRAME_SAMPLES * 4);
        AudioRecord recorder = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes);
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            running.set(false);
            throw new IllegalStateException("Android could not initialize the wake-word microphone.");
        }

        audioRecord = recorder;
        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IllegalStateException("Android did not start the wake-word microphone.");
            }
            Thread thread = new Thread(() -> captureLoop(listener), "Amiki-Hey-Amiki-KWS");
            captureThread = thread;
            thread.start();
        } catch (RuntimeException failure) {
            running.set(false);
            close();
            throw failure;
        }
    }

    private void captureLoop(Listener listener) {
        AudioRecord recorder = audioRecord;
        short[] pcm = new short[FRAME_SAMPLES];
        try {
            while (running.get() && recorder != null) {
                int count = recorder.read(pcm, 0, pcm.length, AudioRecord.READ_BLOCKING);
                if (count > 0) {
                    float[] samples = new float[count];
                    for (int i = 0; i < count; i++) {
                        samples[i] = pcm[i] / 32768.0f;
                    }
                    stream.acceptWaveform(samples, SAMPLE_RATE);
                    while (running.get() && spotter.isReady(stream)) {
                        spotter.decode(stream);
                        String result = spotter.getResult(stream).getKeyword();
                        if (AmikiWakeWordPhrase.isWakePhrase(result)) {
                            spotter.reset(stream);
                            if (running.compareAndSet(true, false)) {
                                listener.onWakeWordDetected();
                            }
                            return;
                        }
                    }
                } else if (count < 0 && running.get()) {
                    throw new IllegalStateException("Android microphone read failed (" + count + ").");
                }
            }
        } catch (RuntimeException failure) {
            if (running.compareAndSet(true, false)) {
                listener.onEngineError(failure);
            }
        } finally {
            running.set(false);
            releaseResources();
        }
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
        running.set(false);
        AudioRecord recorder = audioRecord;
        if (recorder != null) {
            try {
                if (recorder.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                    recorder.stop();
                }
            } catch (RuntimeException ignored) {
            }
        }

        Thread thread = captureThread;
        if (thread != null && thread != Thread.currentThread()) {
            try {
                thread.join(2500L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        if (thread == null || !thread.isAlive()) releaseResources();
    }

    private void releaseResources() {
        if (!resourcesReleased.compareAndSet(false, true)) return;
        AudioRecord recorder = audioRecord;
        audioRecord = null;
        if (recorder != null) {
            try {
                recorder.release();
            } catch (RuntimeException ignored) {
            }
        }
        try {
            stream.release();
        } catch (RuntimeException ignored) {
        }
        try {
            spotter.release();
        } catch (RuntimeException ignored) {
        }
    }
}
