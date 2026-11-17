package com.amiki.app;

import android.app.Activity;
import android.content.Intent;
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
        if (!Intent.ACTION_ASSIST.equals(getIntent().getAction())) {
            finish();
            return;
        }
        if (state == null) startRecognition();
        else recognitionLaunched = state.getBoolean("recognition_launched", false);
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

        // Reuse the existing Core chat/device-planning path. No separate voice
        // execution route is introduced, and existing owner approval remains in force.
        SentinelService.start(this);
        TermuxBackendLauncher.start(this);
        Intent handoff = new Intent(this, MobileShellActivity.class);
        handoff.putExtra(EXTRA_ASSISTANT_REQUEST, command);
        handoff.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(handoff);
        finish();
    }
}
