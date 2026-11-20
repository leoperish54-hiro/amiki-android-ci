package com.amiki.app;

import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.IOException;

/** One bounded, local-only Vosk recognition session. */
final class AmikiOfflineSpeechEngine {
    interface Listener {
        void onPartialResult(String text);
        void onResult(String text);
        void onFinalResult(String text);
        void onError(Exception error);
        void onTimeout();
    }

    private final Model model;
    private final Recognizer recognizer;
    private final SpeechService speechService;
    private boolean closed;

    private AmikiOfflineSpeechEngine(Model model, Recognizer recognizer,
                                     SpeechService speechService) {
        this.model = model;
        this.recognizer = recognizer;
        this.speechService = speechService;
    }

    static AmikiOfflineSpeechEngine create(File modelDirectory) throws IOException {
        if (modelDirectory == null || !new File(modelDirectory, "am/final.mdl").isFile()
                || !new File(modelDirectory, "conf/model.conf").isFile()) {
            throw new IOException("The Amiki offline English model is not installed correctly.");
        }
        Model model = new Model(modelDirectory.getAbsolutePath());
        Recognizer recognizer = null;
        try {
            recognizer = new Recognizer(model, 16000.0f);
            SpeechService service = new SpeechService(recognizer, 16000.0f);
            return new AmikiOfflineSpeechEngine(model, recognizer, service);
        } catch (IOException | RuntimeException failure) {
            if (recognizer != null) recognizer.close();
            model.close();
            throw failure;
        }
    }

    void start(Listener listener) {
        if (listener == null) throw new IllegalArgumentException("A recognition listener is required.");
        synchronized (this) {
            if (closed) throw new IllegalStateException("The offline speech session is closed.");
        }
        boolean started = speechService.startListening(new org.vosk.android.RecognitionListener() {
            @Override public void onPartialResult(String hypothesis) {
                listener.onPartialResult(readText(hypothesis, "partial"));
            }
            @Override public void onResult(String hypothesis) {
                listener.onResult(readText(hypothesis, "text"));
            }
            @Override public void onFinalResult(String hypothesis) {
                listener.onFinalResult(readText(hypothesis, "text"));
            }
            @Override public void onError(Exception error) {
                listener.onError(error);
            }
            @Override public void onTimeout() {
                listener.onTimeout();
            }
        }, 120000);
        if (!started) throw new IllegalStateException("The offline microphone session did not start.");
    }

    private static String readText(String json, String key) {
        if (json == null || json.trim().isEmpty()) return "";
        try {
            return new JSONObject(json).optString(key, "").trim();
        } catch (Exception malformed) {
            return "";
        }
    }

    void close() {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try {
            speechService.cancel();
        } catch (RuntimeException ignored) {
        } finally {
            try {
                speechService.shutdown();
            } catch (RuntimeException ignored) {
            }
            try {
                recognizer.close();
            } catch (RuntimeException ignored) {
            }
            try {
                model.close();
            } catch (RuntimeException ignored) {
            }
        }
    }
}
