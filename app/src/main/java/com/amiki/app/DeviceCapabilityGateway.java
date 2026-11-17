package com.amiki.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Native, on-demand Android capability and launcher-app gateway.
 *
 * The installed-app inventory stays on the device. Discovery is restricted to
 * MAIN/LAUNCHER activities and does not use QUERY_ALL_PACKAGES.
 */
public final class DeviceCapabilityGateway {
    private final Context context;
    private final PackageManager packageManager;

    public DeviceCapabilityGateway(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("A Context is required.");
        }
        Context applicationContext = context.getApplicationContext();
        this.context = applicationContext == null ? context : applicationContext;
        this.packageManager = this.context.getPackageManager();
    }

    public static final class InstalledApp {
        public final String label;
        public final String packageName;

        InstalledApp(String label, String packageName) {
            this.label = label;
            this.packageName = packageName;
        }
    }

    public static final class CapabilitySnapshot {
        public final int apiLevel;
        public final String androidRelease;
        public final boolean cameraAvailable;
        public final boolean microphoneAvailable;
        public final boolean telephonyAvailable;
        public final boolean gpsAvailable;
        public final boolean locationPermissionGranted;
        public final boolean microphonePermissionGranted;
        public final boolean contactsPermissionGranted;

        CapabilitySnapshot(
                int apiLevel,
                String androidRelease,
                boolean cameraAvailable,
                boolean microphoneAvailable,
                boolean telephonyAvailable,
                boolean gpsAvailable,
                boolean locationPermissionGranted,
                boolean microphonePermissionGranted,
                boolean contactsPermissionGranted
        ) {
            this.apiLevel = apiLevel;
            this.androidRelease = androidRelease;
            this.cameraAvailable = cameraAvailable;
            this.microphoneAvailable = microphoneAvailable;
            this.telephonyAvailable = telephonyAvailable;
            this.gpsAvailable = gpsAvailable;
            this.locationPermissionGranted = locationPermissionGranted;
            this.microphonePermissionGranted = microphonePermissionGranted;
            this.contactsPermissionGranted = contactsPermissionGranted;
        }
    }

    public static final class LaunchResult {
        public final boolean accepted;
        public final String message;

        LaunchResult(boolean accepted, String message) {
            this.accepted = accepted;
            this.message = message;
        }
    }

    /**
     * Reports hardware-feature declarations and current runtime permission
     * grants. This is capability metadata, not a live sensor or service test.
     * It never requests permissions.
     */
    public CapabilitySnapshot inspect() {
        boolean locationGranted =
                hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                        || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);

        return new CapabilitySnapshot(
                Build.VERSION.SDK_INT,
                Build.VERSION.RELEASE == null ? "unknown" : Build.VERSION.RELEASE,
                packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY),
                packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE),
                packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY),
                packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS),
                locationGranted,
                hasPermission(Manifest.permission.RECORD_AUDIO),
                hasPermission(Manifest.permission.READ_CONTACTS)
        );
    }

    /**
     * Lists distinct, enabled apps that expose a launcher activity.
     * Android package-visibility filtering still applies.
     */
    public List<InstalledApp> discoverLaunchableApps() {
        Intent launcherQuery = new Intent(Intent.ACTION_MAIN);
        launcherQuery.addCategory(Intent.CATEGORY_LAUNCHER);

        // Do not use MATCH_DEFAULT_ONLY: launcher activities need not declare
        // CATEGORY_DEFAULT. The manifest queries filter scopes visibility.
        List<ResolveInfo> resolved =
                packageManager.queryIntentActivities(launcherQuery, 0);

        Map<String, InstalledApp> unique = new LinkedHashMap<>();
        if (resolved != null) {
            for (ResolveInfo info : resolved) {
                if (info == null || info.activityInfo == null
                        || info.activityInfo.applicationInfo == null
                        || !info.activityInfo.exported
                        || !info.activityInfo.enabled
                        || !info.activityInfo.applicationInfo.enabled) {
                    continue;
                }

                String packageName = info.activityInfo.packageName;
                if (packageName == null
                        || packageName.trim().isEmpty()
                        || context.getPackageName().equals(packageName)) {
                    continue;
                }

                CharSequence rawLabel =
                        packageManager.getApplicationLabel(info.activityInfo.applicationInfo);
                String label = rawLabel == null ? packageName : rawLabel.toString().trim();
                if (label.isEmpty()) {
                    label = packageName;
                }

                unique.putIfAbsent(packageName, new InstalledApp(label, packageName));
            }
        }

        List<InstalledApp> apps = new ArrayList<>(unique.values());
        Collections.sort(apps, Comparator
                .comparing((InstalledApp app) -> normalizeLabelForMatch(app.label))
                .thenComparing(app -> app.packageName));
        return Collections.unmodifiableList(apps);
    }

    /** Returns exact label matches only; partial matches are deliberately refused. */
    public List<InstalledApp> findAppsByLabel(String requestedLabel) {
        return findAppsByLabel(discoverLaunchableApps(), requestedLabel);
    }

    static List<InstalledApp> findAppsByLabel(
            List<InstalledApp> apps,
            String requestedLabel
    ) {
        if (apps == null || requestedLabel == null
                || normalizeLabelForMatch(requestedLabel).isEmpty()) {
            return Collections.emptyList();
        }

        String expected = normalizeLabelForMatch(requestedLabel);
        List<InstalledApp> matches = new ArrayList<>();
        for (InstalledApp app : apps) {
            if (app != null && expected.equals(normalizeLabelForMatch(app.label))) {
                matches.add(app);
            }
        }
        return Collections.unmodifiableList(matches);
    }

    static String normalizeLabelForMatch(String value) {
        return value == null
                ? ""
                : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * Launches only a currently discoverable launcher app. The caller must
     * obtain explicit owner confirmation before invoking this method.
     */
    public LaunchResult launchConfirmedApp(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return new LaunchResult(false, "No app package was selected. Nothing was launched.");
        }

        boolean stillLaunchable = false;
        String label = packageName;
        for (InstalledApp app : discoverLaunchableApps()) {
            if (packageName.equals(app.packageName)) {
                stillLaunchable = true;
                label = app.label;
                break;
            }
        }

        if (!stillLaunchable) {
            return new LaunchResult(
                    false,
                    "That app is no longer visible as launchable. Refresh the list; nothing was launched."
            );
        }

        Intent launchIntent = packageManager.getLaunchIntentForPackage(packageName);
        if (launchIntent == null) {
            return new LaunchResult(
                    false,
                    "Android did not provide a launch activity for " + label + "."
            );
        }

        try {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launchIntent);
            return new LaunchResult(
                    true,
                    "Android accepted the launch request for " + label
                            + ". This confirms the launch request, not the app's internal task."
            );
        } catch (SecurityException failure) {
            return new LaunchResult(
                    false,
                    "Android denied the launch request for " + label + "."
            );
        } catch (RuntimeException failure) {
            return new LaunchResult(
                    false,
                    "The launch request for " + label + " could not be completed."
            );
        }
    }

    private boolean hasPermission(String permission) {
        return Build.VERSION.SDK_INT < 23
                || context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }
}
