package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.provider.Settings;
import android.os.Bundle;
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
    private static final int REQUEST_SPEECH = 7301;
    private boolean recognitionLaunched;

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
        if (state == null) startRecognition();
        else recognitionLaunched = state.getBoolean("recognition_launched", false);
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
                AssistantCommandService.startDeviceTask(this, continuation);
            } catch (RuntimeException denied) {
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
            AssistantCommandService.startDeviceTask(this, command);
        } catch (RuntimeException denied) {
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
        if (recognitionLaunched) return;
        recognitionLaunched = true;
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Android speech recognition is unavailable.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        Intent request = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        request.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        request.putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak your command for Amiki");
        request.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        request.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false);
        try {
            startActivityForResult(request, REQUEST_SPEECH);
        } catch (Exception failure) {
            Toast.makeText(this, "The Android speech recognizer could not be started.", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_SPEECH) return;
        if (resultCode != RESULT_OK || data == null) {
            finish();
            return;
        }
        ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
        String command;
        try {
            command = VoiceAssistantCommandValidator.validate(
                    matches == null || matches.isEmpty() ? null : matches.get(0));
        } catch (IllegalArgumentException invalid) {
            Toast.makeText(this, invalid.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        // Simple launcher/system commands are executed from this explicitly
        // foreground assistant Activity. Device-control requests use the same
        // bounded, observation-driven operator in the foreground service; the
        // main Amiki shell is never opened just to receive a voice command.
        if (handleStopCommand(command)) return;
        if (handleCompoundAppTask(command)) return;
        if (handleDirectLaunch(command)) return;
        if (AssistantRequestClassifier.isDeviceActionRequest(command)) {
            startDeviceTaskWithOwnerReview(command);
            return;
        }
        TermuxBackendLauncher.start(this);
        try {
            AssistantCommandService.start(this, command);
        } catch (RuntimeException denied) {
            Toast.makeText(this,
                    "Android refused the background assistant runtime. No device action was executed.",
                    Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
