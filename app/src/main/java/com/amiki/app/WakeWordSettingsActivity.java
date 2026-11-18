package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.service.voice.VoiceInteractionService;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import ai.picovoice.porcupine.Porcupine;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner-facing configuration for optional local "Hey Amiki" detection.
 * The Picovoice AccessKey is stored only through AmikiSecretStore (AES-GCM,
 * Android Keystore). Model training uses the provider's phrase-model API;
 * keyword detection after provisioning runs locally on the device.
 */
public final class WakeWordSettingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private EditText accessKeyInput;
    private TextView status;
    private Button trainButton;
    private Button enableButton;
    private boolean pendingEnable;
    private static final int REQUEST_AUDIO = 8201;
    private static final int REQUEST_NOTIFICATIONS = 8202;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
        refreshStatus();
    }

    private void render() {
        int pad = dp(18);
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF090D18);

        TextView title = text("HEY AMIKI", 25, 0xFFE9EEFF);
        title.setGravity(Gravity.CENTER_VERTICAL);
        root.addView(title, matchWrap());

        TextView intro = text(
                "Local wake-word detection. Once the model is provisioned, “Hey Amiki” is detected on this phone. Standby audio is not sent to a speech-recognition provider.",
                14, 0xFFB7C2D9);
        root.addView(intro, withTop(intro, 14));

        TextView keyLabel = text("PICOVOICE ACCESS KEY", 12, 0xFF8C9CB8);
        root.addView(keyLabel, withTop(keyLabel, 22));

        accessKeyInput = new EditText(this);
        accessKeyInput.setSingleLine(true);
        accessKeyInput.setHint("Paste your AccessKey");
        accessKeyInput.setTextColor(0xFFE9EEFF);
        accessKeyInput.setHintTextColor(0xFF76839B);
        accessKeyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        accessKeyInput.setBackgroundColor(0xFF172033);
        accessKeyInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        root.addView(accessKeyInput, withTop(accessKeyInput, 8));

        Button saveKey = button("Save AccessKey securely");
        saveKey.setOnClickListener(v -> saveAccessKey());
        root.addView(saveKey, withTop(saveKey, 10));

        trainButton = button("Create “Hey Amiki” wake model");
        trainButton.setOnClickListener(v -> trainModel());
        root.addView(trainButton, withTop(trainButton, 10));

        enableButton = button("Enable hands-free listening");
        enableButton.setOnClickListener(v -> requestEnable());
        root.addView(enableButton, withTop(enableButton, 20));

        Button disable = button("Disable wake word");
        disable.setOnClickListener(v -> {
            AmikiWakeWordService.disableAndStop(this);
            refreshStatus();
        });
        root.addView(disable, withTop(disable, 10));

        status = text("", 13, 0xFF9CB5D9);
        root.addView(status, withTop(status, 20));

        TextView privacy = text(
                "Privacy: listening is opt-in, requires a persistent notification, and can be stopped there at any time. Amiki does not start this microphone service at boot. Model creation contacts Picovoice; wake-word detection itself is local.",
                12, 0xFF8796AF);
        root.addView(privacy, withTop(privacy, 24));

        scroll.addView(root);
        setContentView(scroll);
    }

    private void saveAccessKey() {
        String value = accessKeyInput.getText() == null ? "" : accessKeyInput.getText().toString().trim();
        if (value.length() < 12 || value.length() > 512) {
            status.setText("Enter a valid Picovoice AccessKey.");
            return;
        }
        try {
            AmikiSecretStore.put(this, AmikiWakeWordService.SECRET_ACCESS_KEY, value);
            accessKeyInput.setText("");
            status.setText("AccessKey saved in encrypted Android Keystore-backed storage.");
        } catch (Exception failure) {
            status.setText("The AccessKey could not be stored securely on this device.");
        }
    }

    private void trainModel() {
        String entered = accessKeyInput.getText() == null ? "" : accessKeyInput.getText().toString().trim();
        if (!entered.isEmpty()) {
            try {
                AmikiSecretStore.put(this, AmikiWakeWordService.SECRET_ACCESS_KEY, entered);
                accessKeyInput.setText("");
            } catch (Exception failure) {
                status.setText("The AccessKey could not be stored securely.");
                return;
            }
        }

        final String accessKey;
        try {
            accessKey = AmikiSecretStore.get(this, AmikiWakeWordService.SECRET_ACCESS_KEY);
        } catch (Exception failure) {
            status.setText("The saved AccessKey could not be decrypted. Save it again.");
            return;
        }
        if (accessKey == null || accessKey.isEmpty()) {
            status.setText("Save a Picovoice AccessKey first.");
            return;
        }

        trainButton.setEnabled(false);
        enableButton.setEnabled(false);
        status.setText("Creating the Hey Amiki model. This step requires an internet connection…");
        worker.execute(() -> {
            File target = AmikiWakeWordService.modelFile(this);
            File temporary = new File(getFilesDir(), "hey_amiki.pending.ppn");
            try {
                File parent = target.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    throw new IllegalStateException("Could not create the private model directory.");
                }
                if (temporary.exists() && !temporary.delete()) {
                    throw new IllegalStateException("Could not clear the previous temporary model.");
                }

                Porcupine.trainWakeWordFromPhrase(
                        accessKey, temporary.getAbsolutePath(), "en", "Hey Amiki");

                if (!temporary.isFile() || temporary.length() == 0L) {
                    throw new IllegalStateException("Picovoice returned an empty wake model.");
                }

                // Validate the generated file with the local runtime before publishing it.
                ai.picovoice.porcupine.Porcupine validation = new Porcupine.Builder()
                        .setAccessKey(accessKey)
                        .setKeywordPath(temporary.getAbsolutePath())
                        .setSensitivity(0.55f)
                        .build(getApplicationContext());
                validation.delete();

                if (target.exists() && !target.delete()) {
                    throw new IllegalStateException("Could not replace the previous wake model.");
                }
                if (!temporary.renameTo(target)) {
                    throw new IllegalStateException("Could not move the validated model into private storage.");
                }

                main.post(() -> {
                    status.setText("“Hey Amiki” model created and validated. Wake-word detection will run locally.");
                    trainButton.setEnabled(true);
                    enableButton.setEnabled(true);
                    refreshStatus();
                });
            } catch (Exception failure) {
                if (temporary.exists()) temporary.delete();
                main.post(() -> {
                    status.setText("Wake model creation failed. Check the AccessKey and network, then retry.");
                    trainButton.setEnabled(true);
                    enableButton.setEnabled(true);
                });
            }
        });
    }

    private void requestEnable() {
        if (!AmikiVoiceInteractionService.isSystemActive()) {
            new AlertDialog.Builder(this)
                    .setTitle("Make Amiki the system assistant")
                    .setMessage("Android must have Amiki selected as the active voice assistant before hands-free wake-word invocation can open an assistant session.")
                    .setPositiveButton("Open assistant settings", (dialog, which) -> {
                        try {
                            startActivity(new Intent(Settings.ACTION_VOICE_INPUT_SETTINGS));
                        } catch (RuntimeException unavailable) {
                            startActivity(new Intent(Settings.ACTION_SETTINGS));
                        }
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        if (!AmikiWakeWordService.isConfigured(this)) {
            status.setText("Create the Hey Amiki model before enabling listening.");
            return;
        }
        pendingEnable = true;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQUEST_AUDIO);
            return;
        }
        requestNotificationPermissionOrStart();
    }

    private void requestNotificationPermissionOrStart() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQUEST_NOTIFICATIONS);
            return;
        }
        startWakeWord();
    }

    private void startWakeWord() {
        try {
            AmikiWakeWordService.enableAndStart(this);
            status.setText("Hey Amiki enabled. Use the persistent notification to stop listening.");
            pendingEnable = false;
        } catch (RuntimeException failure) {
            status.setText("Hey Amiki could not be enabled. Check permissions and model configuration.");
            pendingEnable = false;
        }
        refreshStatus();
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (!pendingEnable) return;
        if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
            pendingEnable = false;
            status.setText("Permission denied. Hey Amiki remains disabled.");
            return;
        }
        if (requestCode == REQUEST_AUDIO) {
            requestNotificationPermissionOrStart();
        } else if (requestCode == REQUEST_NOTIFICATIONS) {
            startWakeWord();
        }
    }

    private void refreshStatus() {
        if (status == null) return;
        boolean modelReady = AmikiWakeWordService.isConfigured(this);
        boolean serviceReady = AmikiVoiceInteractionService.isSystemActive();
        String state = AmikiWakeWordService.isListening()
                ? "Listening locally for “Hey Amiki”."
                : AmikiWakeWordService.isEnabled(this)
                    ? "Enabled, currently paused."
                    : "Wake word is disabled.";
        status.setText("VoiceInteractionService active: " + serviceReady
                + "\nWake model ready: " + modelReady
                + "\n" + state);
    }

    @Override
    protected void onDestroy() {
        worker.shutdownNow();
        super.onDestroy();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String value) {
        Button view = new Button(this);
        view.setText(value);
        view.setAllCaps(false);
        return view;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(-1, -2);
    }

    private LinearLayout.LayoutParams withTop(View view, int topDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(topDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
