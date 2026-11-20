package com.amiki.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * On-device launcher-app picker. The app inventory is neither persisted nor
 * sent to Core. Every launch requires a separate local confirmation.
 */
public final class InstalledAppsActivity extends Activity {
    private DeviceCapabilityGateway gateway;
    private ExecutorService discoveryExecutor;
    private LinearLayout appRows;
    private TextView status;
    private TextView capabilitySummary;
    private Button refreshButton;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        gateway = new DeviceCapabilityGateway(this);
        discoveryExecutor = Executors.newSingleThreadExecutor();
        buildUi();
        refreshApps();
    }

    @Override
    protected void onDestroy() {
        if (discoveryExecutor != null) {
            discoveryExecutor.shutdownNow();
        }
        super.onDestroy();
    }

    private void buildUi() {
        int background = 0xFF0E1224;
        int foreground = 0xFFE8EAF6;
        int muted = 0xFFADB4D0;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(22));
        root.setBackgroundColor(background);

        TextView heading = label("Installed Apps", 23, foreground, true);
        root.addView(heading);

        TextView explanation = label(
                "Apps are discovered locally from Android launcher activities. "
                        + "The inventory stays on this phone. Amiki asks before opening an app.",
                13, muted, false
        );
        explanation.setPadding(0, dp(8), 0, dp(14));
        root.addView(explanation);

        refreshButton = new Button(this);
        refreshButton.setText("Refresh app list");
        refreshButton.setAllCaps(false);
        refreshButton.setOnClickListener(v -> refreshApps());
        root.addView(refreshButton, new LinearLayout.LayoutParams(-1, dp(48)));

        capabilitySummary = label("Checking local Android capabilities…", 12, muted, false);
        capabilitySummary.setPadding(0, dp(8), 0, dp(8));
        root.addView(capabilitySummary);

        status = label("Discovering launchable apps…", 12, muted, false);
        status.setPadding(0, dp(12), 0, dp(8));
        root.addView(status);

        appRows = new LinearLayout(this);
        appRows.setOrientation(LinearLayout.VERTICAL);
        root.addView(appRows, new LinearLayout.LayoutParams(-1, -2));

        Button back = new Button(this);
        back.setText("Back to Device Control");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(-1, dp(48));
        backParams.topMargin = dp(14);
        root.addView(back, backParams);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void refreshApps() {
        if (discoveryExecutor == null || discoveryExecutor.isShutdown()) {
            return;
        }

        refreshButton.setEnabled(false);
        status.setText("Discovering launchable apps…");
        appRows.removeAllViews();

        discoveryExecutor.execute(() -> {
            List<DeviceCapabilityGateway.InstalledApp> apps =
                    Collections.emptyList();
            DeviceCapabilityGateway.CapabilitySnapshot snapshot = null;
            String failure = null;
            try {
                snapshot = gateway.inspect();
                apps = gateway.discoverLaunchableApps();
            } catch (RuntimeException error) {
                failure = "Android could not return the launcher-app list. Try refreshing.";
            }

            final List<DeviceCapabilityGateway.InstalledApp> discovered = apps;
            final DeviceCapabilityGateway.CapabilitySnapshot capabilitySnapshot = snapshot;
            final String errorMessage = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }

                refreshButton.setEnabled(true);
                appRows.removeAllViews();

                if (capabilitySnapshot != null) {
                    capabilitySummary.setText(formatCapabilities(capabilitySnapshot));
                }

                if (errorMessage != null) {
                    status.setText(errorMessage);
                    return;
                }

                status.setText(
                        "Found " + discovered.size()
                                + " launchable apps. Availability can change if an app is disabled or removed."
                );

                if (discovered.isEmpty()) {
                    appRows.addView(label(
                            "No launchable apps were returned. Android package visibility "
                                    + "and launcher declarations limit this list.",
                            13, 0xFFADB4D0, false
                    ));
                    return;
                }

                for (DeviceCapabilityGateway.InstalledApp app : discovered) {
                    appRows.addView(createAppRow(app));
                }
            });
        });
    }

    private View createAppRow(DeviceCapabilityGateway.InstalledApp app) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(8), dp(8), dp(8));
        row.setBackgroundColor(0xFF1A2138);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);

        TextView appLabel = label(app.label, 15, 0xFFE8EAF6, true);
        TextView packageName = label(app.packageName, 10, 0xFFADB4D0, false);
        details.addView(appLabel);
        details.addView(packageName);
        row.addView(details, new LinearLayout.LayoutParams(0, -2, 1f));

        Button open = new Button(this);
        open.setText("Open");
        open.setAllCaps(false);
        open.setOnClickListener(v -> confirmLaunch(app));
        row.addView(open, new LinearLayout.LayoutParams(-2, dp(44)));

        LinearLayout.LayoutParams rowParams =
                new LinearLayout.LayoutParams(-1, -2);
        rowParams.bottomMargin = dp(7);
        row.setLayoutParams(rowParams);
        return row;
    }

    private void confirmLaunch(DeviceCapabilityGateway.InstalledApp app) {
        new AlertDialog.Builder(this)
                .setTitle("Open " + app.label + "?")
                .setMessage(
                        "Package: " + app.packageName
                                + "\n\nAmiki will ask Android to open this app. "
                                + "This does not grant Amiki access to read or control its contents."
                )
                .setNegativeButton("Cancel", (dialog, which) ->
                        status.setText("Launch cancelled. Nothing was opened by Amiki."))
                .setPositiveButton("Open app", (dialog, which) -> {
                    DeviceCapabilityGateway.LaunchResult result =
                            gateway.launchConfirmedApp(app.packageName);
                    status.setText(result.message);
                })
                .show();
    }

    private String formatCapabilities(DeviceCapabilityGateway.CapabilitySnapshot snapshot) {
        return "Android " + snapshot.androidRelease + " (API " + snapshot.apiLevel + ")"
                + "\nHardware features — camera: " + yesNo(snapshot.cameraAvailable)
                + ", microphone: " + yesNo(snapshot.microphoneAvailable)
                + ", GPS: " + yesNo(snapshot.gpsAvailable)
                + ", telephony: " + yesNo(snapshot.telephonyAvailable)
                + "\nPermission grants — location: " + yesNo(snapshot.locationPermissionGranted)
                + ", microphone: " + yesNo(snapshot.microphonePermissionGranted)
                + ", contacts: " + yesNo(snapshot.contactsPermissionGranted)
                + "\nThese are declarations and current grants, not live sensor tests.";
    }

    private String yesNo(boolean value) {
        return value ? "available/granted" : "not available/granted";
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(null, Typeface.BOLD);
        }
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
