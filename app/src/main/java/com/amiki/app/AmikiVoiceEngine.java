package com.amiki.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

/**
 * Amiki-owned voice-provider selection and lifecycle boundary.
 *
 * Android's system recognizer is an optional compatibility provider. On-device
 * Android recognition is selected only when the platform explicitly reports it
 * available. A future bundled Amiki model provider can be added behind this
 * boundary without changing assistant command dispatch.
 */
public final class AmikiVoiceEngine {
    private static final String PREFERENCES = "amiki_voice_engine";
    private static final String KEY_MODE = "recognition_mode";

    public enum Mode {
        AUTO,
        ON_DEVICE,
        SYSTEM,
        OFFLINE
    }

    public enum Provider {
        ON_DEVICE,
        SYSTEM,
        OFFLINE,
        UNAVAILABLE
    }

    public static final class Selection {
        public final Mode requestedMode;
        public final Provider provider;
        public final int androidApi;

        private Selection(Mode requestedMode, Provider provider, int androidApi) {
            this.requestedMode = requestedMode;
            this.provider = provider;
            this.androidApi = androidApi;
        }

        public boolean isAvailable() {
            return provider != Provider.UNAVAILABLE;
        }

        public String description() {
            switch (provider) {
                case ON_DEVICE:
                    return "Android on-device speech recognition";
                case SYSTEM:
                    return "Android system speech provider";
                case OFFLINE:
                    return "Amiki offline English model (runs locally; no network during recognition)";
                default:
                    return "Requested on-device recognizer unavailable";
            }
        }
    }

    private AmikiVoiceEngine() { }

    public static Mode getMode(Context context) {
        if (context == null) return Mode.AUTO;
        String stored = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_MODE, Mode.AUTO.name());
        try {
            return Mode.valueOf(stored == null ? Mode.AUTO.name() : stored);
        } catch (IllegalArgumentException invalid) {
            return Mode.AUTO;
        }
    }

    public static void setMode(Context context, Mode mode) {
        if (context == null || mode == null) {
            throw new IllegalArgumentException("A context and voice recognition mode are required.");
        }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MODE, mode.name())
                .apply();
    }

    public static Selection resolve(Context context) {
        if (context == null) {
            return new Selection(Mode.AUTO, Provider.UNAVAILABLE, Build.VERSION.SDK_INT);
        }
        Mode mode = getMode(context);
        boolean onDeviceAvailable = false;
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                onDeviceAvailable = SpeechRecognizer.isOnDeviceRecognitionAvailable(context);
            } catch (RuntimeException ignored) {
                onDeviceAvailable = false;
            }
        }
        boolean offlineAvailable = AmikiOfflineSpeechModel.isInstalled(context);
        return new Selection(mode,
                selectProvider(mode, Build.VERSION.SDK_INT, onDeviceAvailable, offlineAvailable),
                Build.VERSION.SDK_INT);
    }

    /** Pure provider policy, exposed for deterministic unit tests. */
    static Provider selectProvider(Mode mode, int sdkInt, boolean onDeviceAvailable) {
        return selectProvider(mode, sdkInt, onDeviceAvailable, false);
    }

    static Provider selectProvider(Mode mode, int sdkInt, boolean onDeviceAvailable,
                                   boolean offlineModelAvailable) {
        Mode requested = mode == null ? Mode.AUTO : mode;
        if (requested == Mode.SYSTEM) return Provider.SYSTEM;
        if (requested == Mode.OFFLINE) {
            return offlineModelAvailable ? Provider.OFFLINE : Provider.UNAVAILABLE;
        }
        if (requested == Mode.ON_DEVICE) {
            return sdkInt >= 31 && onDeviceAvailable
                    ? Provider.ON_DEVICE : Provider.UNAVAILABLE;
        }
        if (sdkInt >= 31 && onDeviceAvailable) return Provider.ON_DEVICE;
        if (offlineModelAvailable) return Provider.OFFLINE;
        return Provider.SYSTEM;
    }

    /** Must be called from the Android main thread, as required by SpeechRecognizer. */
    public static SpeechRecognizer createRecognizer(Context context, Selection selection) {
        if (context == null || selection == null || !selection.isAvailable()) return null;
        if (selection.provider == Provider.ON_DEVICE) {
            if (Build.VERSION.SDK_INT < 31) return null;
            try {
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context);
            } catch (RuntimeException unavailable) {
                if (selection.requestedMode == Mode.AUTO) {
                    try {
                        return SpeechRecognizer.createSpeechRecognizer(context);
                    } catch (RuntimeException noSystemProvider) {
                        return null;
                    }
                }
                return null;
            }
        }
        return SpeechRecognizer.createSpeechRecognizer(context);
    }

    public static Intent createRecognitionRequest() {
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        // A provider may ignore this hint. Amiki never treats it as proof that
        // audio stayed on-device; a bundled local model is a separate milestone.
        request.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        return request;
    }
}
