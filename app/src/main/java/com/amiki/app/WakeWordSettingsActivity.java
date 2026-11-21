package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Owner-facing setup and controls for Amiki's optional local "Hey Amiki" detector. */
public final class WakeWordSettingsActivity extends Activity {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private TextView status;
    private Button installButton;
    private Button enableButton;
    private ProgressBar progress;
    private boolean pendingEnable;

    private static final int REQUEST_AUDIO = 8201;
    private static final int REQUEST_NOTIFICATIONS = 8202;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        render();
        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
                "Amiki's own on-device wake-word detector. Termux securely provisions the English model; Amiki imports and verifies it, then performs all microphone capture and detection locally. Termux must allow external app commands. No Picovoice account or AccessKey is required.",
                14, 0xFFB7C2D9);
        root.addView(intro, withTop(intro, 14));

        installButton = button("Install / repair local wake-word model");
        installButton.setOnClickListener(v -> installModel());
        root.addView(installButton, withTop(installButton, 22));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(0);
        progress.setVisibility(View.GONE);
        root.addView(progress, withTop(progress, 10));

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
                "Privacy: detection runs on this phone. Standby microphone audio is not uploaded or sent to a speech recognizer. Listening is off until you enable it, requires microphone permission and a persistent notification, and can be stopped from that notification. Amiki does not start wake-word listening from boot.",
                12, 0xFF8796AF);
        root.addView(privacy, withTop(privacy, 24));

        scroll.addView(root);
        setContentView(scroll);
    }

    private void installModel() {
        installButton.setEnabled(false);
        enableButton.setEnabled(false);
        progress.setVisibility(View.VISIBLE);
        progress.setProgress(0);
        status.setText("Preparing the verified local wake-word model…");

        worker.execute(() -> {
            try {
                AmikiWakeWordModel.install(this, (percentage, detail) -> main.post(() -> {
                    if (progress == null) return;
                    progress.setProgress(percentage);
                    status.setText(detail);
                }));
                AmikiWakeWordService.clearLegacyPicovoiceData(this);
                main.post(() -> {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    installButton.setEnabled(true);
                    enableButton.setEnabled(true);
                    status.setText("Hey Amiki's local model is installed and verified. No AccessKey is needed.");
                    refreshStatus();
                });
            } catch (Exception failure) {
                android.util.Log.w("AmikiWakeWord", "Could not install the local wake-word model.", failure);
                main.post(() -> {
                    if (isFinishing()) return;
                    progress.setVisibility(View.GONE);
                    installButton.setEnabled(true);
                    enableButton.setEnabled(true);
                    String detail = failure.getMessage();
                    if (detail == null || detail.trim().isEmpty()) {
                        detail = failure.getClass().getSimpleName();
                    }
                    status.setText("Model installation failed.\n" + detail);
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
            status.setText("Install the local Hey Amiki model before enabling listening.");
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
            status.setText("Hey Amiki could not be enabled. Check permissions and local model installation.");
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
                    ? "Enabled, currently paused or starting."
                    : "Wake word is disabled.";
        status.setText("VoiceInteractionService active: " + serviceReady
                + "\nLocal wake-word model ready: " + modelReady
                + "\n" + state);
        if (enableButton != null) enableButton.setEnabled(modelReady);
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
