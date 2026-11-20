package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Entry point invoked by Android when the owner selects Amiki as the assistant
 * and uses the system assistant gesture. Speech capture starts only after this
 * explicit system invocation; this activity is not an ambient wake-word engine.
 */
public final class AssistantEntryActivity extends Activity {
    static final String EXTRA_ASSISTANT_REQUEST = "com.amiki.app.extra.ASSISTANT_REQUEST";
    private boolean recognitionLaunched;
    private boolean handedOffToRuntime;
    private volatile boolean activityDestroyed;
    private boolean offlinePermissionPending;
    private boolean offlineRecognitionComplete;
    private AmikiOfflineSpeechEngine offlineEngine;
    private static final int REQUEST_OFFLINE_AUDIO = 8127;
    private SpeechRecognizer activeRecognizer;
    private AlertDialog listeningDialog;
    private AmikiVoiceEngine.Selection voiceSelection;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final VoiceTranscriptAssembler transcriptAssembler = new VoiceTranscriptAssembler();
    private final AmikiVoiceSession voiceSession = new AmikiVoiceSession();
    private Runnable transcriptFlush;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (!Intent.ACTION_ASSIST.equals(getIntent().getAction())
                || !AssistantRoleGateway.isHeld(this)) {
            // The exported assist handler is inert unless Amiki currently
            // holds the assistant role. ACTION_ASSIST is an intent contract,
            // not a cryptographic proof of caller identity; all consequential
            // UI actions still require the normal owner/UOSA approval path.
            finish();
            return;
        }
        // Release the optional local keyword detector before command STT opens the microphone.
        AmikiWakeWordService.pauseForAssistant();
        // A fresh generation owns this assistant turn; stale callbacks are ignored.
        voiceSession.beginCommand();
        recognitionLaunched = false;
        startRecognition();
    }

    private boolean handleStopCommand(String command) {
        if (command == null) return false;
        String normalized = command.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[.!?]+$", "")
                .replaceAll("\\s+", " ");
        if (normalized.equals("stop")
                || normalized.equals("stop amiki")
                || normalized.equals("stop the task")
                || normalized.equals("stop current task")
                || normalized.equals("cancel task")
                || normalized.equals("cancel current task")
                || normalized.equals("cancel the task")
                || normalized.equals("cancel amiki")) {
            AssistantCommandService.requestStop(this);
            Toast.makeText(this, "Amiki stop request sent.", Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
        return false;
    }

    private boolean handleCompoundAppTask(String command) {
        if (command == null) return false;
        String normalized = command.trim();
        Pattern openAndContinue = Pattern.compile(
                "^(?:please\\s+)?(?:open|launch|start)\\s+(.{1,96}?)\\s+and\\s+(.{1,1000})$",
                Pattern.CASE_INSENSITIVE);
        Pattern searchInApp = Pattern.compile(
                "^(?:please\\s+)?(?:search|find)\\s+(.{1,96}?)\\s+for\\s+(.{1,500})$",
                Pattern.CASE_INSENSITIVE);
        Matcher match = openAndContinue.matcher(normalized);
        boolean search = false;
        if (!match.matches()) {
            match = searchInApp.matcher(normalized);
            search = match.matches();
        }
        if (!match.matches()) return false;

        String requestedLabel = match.group(1).trim();
        String continuation = search
                ? "search for " + match.group(2).trim()
                : match.group(2).trim();
        if (requestedLabel.isEmpty() || continuation.isEmpty()) return false;

        DeviceCapabilityGateway gateway = new DeviceCapabilityGateway(this);
        java.util.List<DeviceCapabilityGateway.InstalledApp> matches =
                gateway.findAppsByLabel(requestedLabel);
        if (matches.isEmpty()) {
            Toast.makeText(this,
                    "No exact launchable app named " + requestedLabel + " was found. No app was opened.",
                    Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
        if (matches.size() == 1) {
            launchAppAndStartTask(gateway, matches.get(0), continuation);
            return true;
        }

        String[] choices = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            DeviceCapabilityGateway.InstalledApp app = matches.get(i);
            choices[i] = app.label + " (" + app.packageName + ")";
        }
        final String taskContinuation = continuation;
        new AlertDialog.Builder(this)
                .setTitle("Choose the exact installed app")
                .setItems(choices, (dialog, which) -> {
                    if (which >= 0 && which < matches.size()) {
                        launchAppAndStartTask(gateway, matches.get(which), taskContinuation);
                    } else {
                        finish();
                    }
                })
                .setOnCancelListener(dialog -> finish())
                .show();
        return true;
    }

    private void launchAppAndStartTask(
            DeviceCapabilityGateway gateway,
            DeviceCapabilityGateway.InstalledApp app,
            String continuation
    ) {
        if (DeviceTaskJournal.hasPendingOwnerReview(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Review the previous device action")
                    .setMessage("A previous UI action has an unresolved outcome. Inspect the phone and the target app first. Continuing will not retry that action; it only acknowledges the unknown result before starting this new request.")
                    .setPositiveButton("I inspected; continue", (dialog, which) -> {
                        try {
                            DeviceTaskJournal.acknowledgePendingOwnerReview(this);
                            launchAppAndStartTask(gateway, app, continuation);
                        } catch (RuntimeException failure) {
                            Toast.makeText(this, "The previous task could not be safely acknowledged.", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> finish())
                    .setOnCancelListener(dialog -> finish())
                    .show();
            return;
        }

        DeviceCapabilityGateway.LaunchResult result = gateway.launchConfirmedApp(app.packageName);
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
        if (result.accepted) {
            try {
                TermuxBackendLauncher.start(this);
                handedOffToRuntime = true;
                AssistantCommandService.startDeviceTask(this, continuation);
            } catch (RuntimeException denied) {
                handedOffToRuntime = false;
                Toast.makeText(this,
                        "The background task runtime could not start. Only the app launch was requested.",
                        Toast.LENGTH_LONG).show();
            }
        }
        finish();
    }

    private void startDeviceTaskWithOwnerReview(String command) {
        if (DeviceTaskJournal.hasPendingOwnerReview(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("Review the previous device action")
                    .setMessage("A previous UI action has an unresolved outcome. Inspect the phone and the target app first. Continuing will not retry that action; it only acknowledges the unknown result before starting this new request.")
                    .setPositiveButton("I inspected; continue", (dialog, which) -> {
                        try {
                            DeviceTaskJournal.acknowledgePendingOwnerReview(this);
                            startDeviceTaskNow(command);
                        } catch (RuntimeException failure) {
                            Toast.makeText(this, "The previous task could not be safely acknowledged.", Toast.LENGTH_LONG).show();
                            finish();
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> finish())
                    .setOnCancelListener(dialog -> finish())
                    .show();
            return;
        }
        startDeviceTaskNow(command);
    }

    private void startDeviceTaskNow(String command) {
        try {
            TermuxBackendLauncher.start(this);
            handedOffToRuntime = true;
            AssistantCommandService.startDeviceTask(this, command);
        } catch (RuntimeException denied) {
            handedOffToRuntime = false;
            Toast.makeText(this,
                    "Android refused the background device-task runtime. No UI action was executed.",
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private boolean handleDirectLaunch(String command) {
        DeviceCommandParser.Command parsed = DeviceCommandParser.parse(command);
        if (parsed == null) return false;

        if (parsed.action == DeviceCommandParser.Action.OPEN_SETTINGS) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (RuntimeException failure) {
                Toast.makeText(this, "Android Settings could not be opened.", Toast.LENGTH_LONG).show();
            }
            finish();
            return true;
        }

        if (parsed.action != DeviceCommandParser.Action.OPEN_APP
                && parsed.action != DeviceCommandParser.Action.OPEN_CHROME) {
            Toast.makeText(this,
                    "That device-control action is not connected to the background operator yet. No action was performed.",
                    Toast.LENGTH_LONG).show();
            finish();
            return true;
        }

        String requestedLabel;
        if (parsed.action == DeviceCommandParser.Action.OPEN_APP) {
            requestedLabel = parsed.argument;
        } else if (parsed.action == DeviceCommandParser.Action.OPEN_CHROME) {
            requestedLabel = "Chrome";
        } else {
            return false;
        }

        DeviceCapabilityGateway gateway = new DeviceCapabilityGateway(this);
        java.util.List<DeviceCapabilityGateway.InstalledApp> matches =
                gateway.findAppsByLabel(requestedLabel);
        if (matches.isEmpty()) {
            Toast.makeText(this,
                    "No exact launchable app named " + requestedLabel + " was found.",
                    Toast.LENGTH_LONG).show();
            finish();
            return true;
        }
        if (matches.size() == 1) {
            launchSelectedApp(gateway, matches.get(0));
            return true;
        }

        String[] choices = new String[matches.size()];
        for (int i = 0; i < matches.size(); i++) {
            DeviceCapabilityGateway.InstalledApp app = matches.get(i);
            choices[i] = app.label + " (" + app.packageName + ")";
        }
        new AlertDialog.Builder(this)
                .setTitle("Choose the exact installed app")
                .setItems(choices, (dialog, which) -> {
                    if (which >= 0 && which < matches.size()) {
                        launchSelectedApp(gateway, matches.get(which));
                    } else {
                        finish();
                    }
                })
                .setOnCancelListener(dialog -> finish())
                .show();
        return true;
    }

    private void launchSelectedApp(
            DeviceCapabilityGateway gateway,
            DeviceCapabilityGateway.InstalledApp app
    ) {
        DeviceCapabilityGateway.LaunchResult result = gateway.launchConfirmedApp(app.packageName);
        Toast.makeText(this, result.message, Toast.LENGTH_LONG).show();
        finish();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putBoolean("recognition_launched", recognitionLaunched);
        super.onSaveInstanceState(outState);
    }

    private void startRecognition() {
        if (recognitionLaunched || isFinishing()) return;
        recognitionLaunched = true;
        try {
            voiceSelection = AmikiVoiceEngine.resolve(this);
        } catch (RuntimeException providerFailure) {
            Toast.makeText(this,
                    "Amiki could not resolve the selected speech provider. No command was executed.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (voiceSelection == null) {
            Toast.makeText(this,
                    "Amiki could not select a speech provider. No command was executed.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (voiceSelection.provider == AmikiVoiceEngine.Provider.OFFLINE) {
            startOfflineRecognition();
            return;
        }
        if (!voiceSelection.isAvailable()) {
            Toast.makeText(this,
                    "The selected local speech recognizer is not installed. Download Amiki Offline English in Settings.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (voiceSelection.provider == AmikiVoiceEngine.Provider.SYSTEM
                && !SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "No Android system speech-recognition provider is available.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        try {
            activeRecognizer = AmikiVoiceEngine.createRecognizer(this, voiceSelection);
            if (activeRecognizer == null) {
                throw new IllegalStateException("The selected speech provider could not be created.");
            }
            activeRecognizer.setRecognitionListener(new RecognitionListener() {
                @Override public void onReadyForSpeech(Bundle params) {
                    updateListeningMessage("Listening for your command…");
                }
                @Override public void onBeginningOfSpeech() {
                    updateListeningMessage("Hearing you…");
                }
                @Override public void onRmsChanged(float rmsdB) { }
                @Override public void onBufferReceived(byte[] buffer) { }
                @Override public void onEndOfSpeech() {
                    updateListeningMessage("Collecting your thought…");
                }
                @Override public void onError(int error) {
                    if (!transcriptAssembler.heldText().isEmpty()) {
                        VoiceTranscriptAssembler.Update update = transcriptAssembler.flush();
                        if (update.isComplete()) {
                            completeTranscript(update.completedText);
                            return;
                        }
                    }
                    handleRecognitionError(error);
                }
                @Override public void onResults(Bundle results) {
                    ArrayList<String> matches = results == null ? null
                            : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                    acceptTranscript(matches == null || matches.isEmpty() ? null : matches.get(0));
                }
                @Override public void onPartialResults(Bundle partialResults) {
                    if (partialResults == null) return;
                    ArrayList<String> partial = partialResults.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION);
                    if (partial != null && !partial.isEmpty()) {
                        updateListeningMessage("Hearing: " + boundedVoiceText(partial.get(0)));
                    }
                }
                @Override public void onEvent(int eventType, Bundle params) { }
            });

            showListeningDialog(voiceSelection.description());
            activeRecognizer.startListening(AmikiVoiceEngine.createRecognitionRequest());
        } catch (RuntimeException failure) {
            dismissListeningDialog();
            destroyRecognizer(true);
            Toast.makeText(this,
                    "Amiki could not start the selected speech provider. No command was executed.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void startOfflineRecognition() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            offlinePermissionPending = true;
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO},
                    REQUEST_OFFLINE_AUDIO);
            return;
        }
        if (!AmikiOfflineSpeechModel.isInstalled(this)) {
            Toast.makeText(this,
                    "Amiki Offline English is not installed. Download it from Voice Provider Settings first.",
                    Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        showListeningDialog(voiceSelection.description());
        updateListeningMessage("Loading Amiki's local English model…");
        new Thread(() -> {
            final AmikiOfflineSpeechEngine created;
            try {
                created = AmikiOfflineSpeechEngine.create(
                        AmikiOfflineSpeechModel.modelDirectory(this));
            } catch (OutOfMemoryError memoryFailure) {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(this,
                                "The offline speech model could not fit in available memory. Close other apps or select another provider.",
                                Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
                return;
            } catch (Exception failure) {
                mainHandler.post(() -> {
                    if (!isFinishing()) {
                        Toast.makeText(this,
                                "Amiki's offline speech engine could not be initialized. No command was executed.",
                                Toast.LENGTH_LONG).show();
                        finish();
                    }
                });
                return;
            }
            mainHandler.post(() -> {
                if (activityDestroyed || isFinishing()) {
                    new Thread(created::close, "Amiki-Vosk-Cleanup").start();
                    return;
                }
                offlineEngine = created;
                try {
                    updateListeningMessage("Listening locally. Speech is not sent to a network provider.");
                    created.start(new AmikiOfflineSpeechEngine.Listener() {
                        @Override public void onPartialResult(String text) {
                            if (!activityDestroyed && !offlineRecognitionComplete && text != null && !text.isEmpty()) {
                                updateListeningMessage("Hearing: " + boundedVoiceText(text));
                            }
                        }
                        @Override public void onResult(String text) {
                            if (!activityDestroyed && !offlineRecognitionComplete && text != null && !text.isEmpty()) {
                                acceptTranscript(text);
                            }
                        }
                        @Override public void onFinalResult(String text) {
                            if (activityDestroyed || offlineRecognitionComplete) return;
                            if (text != null && !text.trim().isEmpty()) acceptTranscript(text);
                            mainHandler.postDelayed(() -> {
                                if (activityDestroyed || offlineRecognitionComplete) return;
                                VoiceTranscriptAssembler.Update update = transcriptAssembler.flush();
                                if (update.isComplete()) completeTranscript(update.completedText);
                                else handleRecognitionError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                            }, 250L);
                        }
                        @Override public void onError(Exception error) {
                            if (activityDestroyed || offlineRecognitionComplete) return;
                            if (!transcriptAssembler.heldText().isEmpty()) {
                                VoiceTranscriptAssembler.Update update = transcriptAssembler.flush();
                                if (update.isComplete()) completeTranscript(update.completedText);
                                else handleRecognitionError(SpeechRecognizer.ERROR_AUDIO);
                            } else {
                                handleRecognitionError(SpeechRecognizer.ERROR_AUDIO);
                            }
                        }
                        @Override public void onTimeout() {
                            if (activityDestroyed || offlineRecognitionComplete) return;
                            VoiceTranscriptAssembler.Update update = transcriptAssembler.flush();
                            if (update.isComplete()) completeTranscript(update.completedText);
                            else handleRecognitionError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT);
                        }
                    });
                } catch (RuntimeException failure) {
                    destroyOfflineRecognition();
                    dismissListeningDialog();
                    Toast.makeText(this,
                            "The offline microphone could not start. No command was executed.",
                            Toast.LENGTH_LONG).show();
                    finish();
                }
            });
        }, "Amiki-Vosk-Model-Loader").start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_OFFLINE_AUDIO || !offlinePermissionPending) return;
        offlinePermissionPending = false;
        if (grantResults.length > 0
                && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            startOfflineRecognition();
        } else {
            Toast.makeText(this, "Microphone permission was denied. Offline recognition remains disabled.",
                    Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void destroyOfflineRecognition() {
        AmikiOfflineSpeechEngine engine = offlineEngine;
        offlineEngine = null;
        if (engine != null) {
            new Thread(engine::close, "Amiki-Vosk-Cleanup").start();
        }
    }

    /**
     * Jarvis-style endpointing: a recognized fragment is not automatically a
     * complete command. Pauses after conjunctions/prepositions are held while
     * the recognizer is restarted to capture the rest of the thought.
     */
    private void acceptTranscript(String fragment) {
        if (fragment == null || fragment.trim().isEmpty()) {
            handleRecognitionError(SpeechRecognizer.ERROR_NO_MATCH);
            return;
        }
        VoiceTranscriptAssembler.Update update = transcriptAssembler.feed(
                fragment, false, SystemClock.elapsedRealtime());
        if (update.isComplete()) {
            completeTranscript(update.completedText);
            return;
        }

        updateListeningMessage("Heard: " + boundedVoiceText(update.heldText)
                + "\nContinue speaking…");
        scheduleTranscriptFlush(update.waitMillis);
        mainHandler.postDelayed(this::restartRecognitionSegment, 220L);
    }

    private void scheduleTranscriptFlush(long delayMillis) {
        if (transcriptFlush != null) mainHandler.removeCallbacks(transcriptFlush);
        transcriptFlush = () -> {
            transcriptFlush = null;
            VoiceTranscriptAssembler.Update update = transcriptAssembler.flush();
            if (update.isComplete()) completeTranscript(update.completedText);
        };
        mainHandler.postDelayed(transcriptFlush, Math.max(1L, delayMillis));
    }

    private void restartRecognitionSegment() {
        if (isFinishing() || activeRecognizer == null) return;
        try {
            activeRecognizer.startListening(AmikiVoiceEngine.createRecognitionRequest());
        } catch (RuntimeException failure) {
            if (!transcriptAssembler.heldText().isEmpty()) {
                VoiceTranscriptAssembler.Update update = transcriptAssembler.flush();
                if (update.isComplete()) completeTranscript(update.completedText);
            } else {
                handleRecognitionError(SpeechRecognizer.ERROR_CLIENT);
            }
        }
    }

    private void completeTranscript(String transcript) {
        offlineRecognitionComplete = true;
        destroyOfflineRecognition();
        if (transcriptFlush != null) mainHandler.removeCallbacks(transcriptFlush);
        transcriptFlush = null;
        transcriptAssembler.cancel();
        dismissListeningDialog();
        destroyRecognizer(false);
        handleRecognizedCommand(transcript);
    }

    private String boundedVoiceText(String value) {
        if (value == null) return "";
        String clean = value.trim();
        return clean.length() <= 180 ? clean : clean.substring(0, 180) + "…";
    }

    private void showListeningDialog(String providerDescription) {
        listeningDialog = new AlertDialog.Builder(this)
                .setTitle("Amiki")
                .setMessage("Listening for your command…\n" + providerDescription
                        + (voiceSelection != null && voiceSelection.provider == AmikiVoiceEngine.Provider.OFFLINE
                            ? "\nYour speech is transcribed locally on this phone."
                            : "\nThe selected system provider may use its own network services."))
                .setNegativeButton("Cancel", (dialog, which) -> {
                    destroyRecognizer(true);
                    finish();
                })
                .setOnCancelListener(dialog -> {
                    destroyRecognizer(true);
                    finish();
                })
                .create();
        listeningDialog.show();
    }

    private void updateListeningMessage(String message) {
        if (listeningDialog != null && listeningDialog.isShowing()) {
            listeningDialog.setMessage(message + "\n" + voiceSelection.description());
        }
    }

    private void dismissListeningDialog() {
        if (listeningDialog != null && listeningDialog.isShowing()) {
            listeningDialog.dismiss();
        }
        listeningDialog = null;
    }

    private void destroyRecognizer(boolean cancel) {
        SpeechRecognizer recognizer = activeRecognizer;
        activeRecognizer = null;
        if (recognizer == null) return;
        if (cancel) {
            try { recognizer.cancel(); } catch (RuntimeException ignored) { }
        }
        try { recognizer.destroy(); } catch (RuntimeException ignored) { }
    }

    private void handleRecognitionError(int error) {
        dismissListeningDialog();
        destroyRecognizer(false);
        String message;
        switch (error) {
            case SpeechRecognizer.ERROR_AUDIO:
                message = "The microphone could not be read.";
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                message = "Microphone permission is required for voice commands.";
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                message = "The selected recognition provider could not complete the request.";
                break;
            case SpeechRecognizer.ERROR_NO_MATCH:
                message = "I didn't catch that. Please try again.";
                break;
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                message = "The speech provider is busy. Try again in a moment.";
                break;
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                message = "I didn't hear a command.";
                break;
            default:
                message = "Speech recognition failed (code " + error + ").";
                break;
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        finish();
    }

    private void handleRecognizedCommand(String recognizedText) {
        String command;
        try {
            command = VoiceAssistantCommandValidator.validate(recognizedText);
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, invalid.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Simple launcher/system commands are executed from this explicitly
        // foreground assistant Activity. Device-control requests use the same
        // bounded, observation-driven operator and owner-approval pipeline.
        if (handleStopCommand(command)) return;
        if (handleCompoundAppTask(command)) return;
        if (handleDirectLaunch(command)) return;
        if (AssistantRequestClassifier.isDeviceActionRequest(command)) {
            startDeviceTaskWithOwnerReview(command);
            return;
        }
        try {
            TermuxBackendLauncher.start(this);
            handedOffToRuntime = true;
            AssistantCommandService.start(this, command);
        } catch (RuntimeException denied) {
            handedOffToRuntime = false;
            Toast.makeText(this,
                    "Android refused the background assistant runtime. No device action was executed.",
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        activityDestroyed = true;
        offlineRecognitionComplete = true;
        destroyOfflineRecognition();
        if (transcriptFlush != null) mainHandler.removeCallbacks(transcriptFlush);
        transcriptFlush = null;
        transcriptAssembler.cancel();
        dismissListeningDialog();
        destroyRecognizer(true);
        if (!handedOffToRuntime && !AssistantCommandService.isBusy()) {
            AmikiWakeWordService.resumeIfEnabled();
        }
        super.onDestroy();
    }
}
