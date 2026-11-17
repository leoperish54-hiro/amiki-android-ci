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
            // The exported assist handler is inert unless the OS currently
            // grants Amiki the assistant role. An ordinary app cannot spoof
            // the assistant gesture by sending ACTION_ASSIST itself.
            finish();
            return;
        }
        if (state == null) startRecognition();
        else recognitionLaunched = state.getBoolean("recognition_launched", false);
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
        // foreground assistant Activity. Complex requests are handed to the
        // bounded foreground service and normal Core chat path; the main Amiki
        // shell is never opened just to receive a voice command.
        if (handleDirectLaunch(command)) return;
        if (AssistantRequestClassifier.isDeviceActionRequest(command)) {
            Toast.makeText(this,
                    "That device-control action is not connected to the background operator yet. No action was performed.",
                    Toast.LENGTH_LONG).show();
            finish();
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
