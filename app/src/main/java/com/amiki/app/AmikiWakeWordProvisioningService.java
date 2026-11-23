package com.amiki.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Amiki-owned lifecycle for wake-word model provisioning.
 *
 * The settings Activity is only a controller. This foreground service owns the
 * install attempt, persists checkpoints, and can be recreated by Android after
 * process reclamation (START_STICKY is best-effort, not a force-stop guarantee).
 */
public final class AmikiWakeWordProvisioningService extends Service {
    static final String ACTION_START = "com.amiki.app.action.START_WAKE_WORD_PROVISIONING";
    static final String ACTION_CANCEL = "com.amiki.app.action.CANCEL_WAKE_WORD_PROVISIONING";

    private static final String PREFS = "amiki_wake_word_provisioning";
    private static final String KEY_STATE = "state";
    private static final String KEY_PROGRESS = "progress";
    private static final String KEY_DETAIL = "detail";
    private static final String KEY_UPDATED = "updated_at";
    private static final String KEY_SESSION_ID = "session_id";
    private static final String CHANNEL_ID = "amiki_wake_word_provisioning";
    private static final int NOTIFICATION_ID = 8401;
    private static final int MAX_SESSION_ATTEMPTS = 5;

    private final Object lock = new Object();
    private ExecutorService executor;
    private volatile boolean cancelled;
    private boolean running;
    private String lastNotifiedState = "";
    private int lastNotifiedProgress = -10;

    public static void start(Context context) {
        SharedPreferences prefs = preferences(context);
        if (!isActive(context)) {
            if (!prefs.edit().putString(KEY_SESSION_ID, UUID.randomUUID().toString()).commit()) {\n                throw new IllegalStateException("Could not persist the wake-word provisioning session ID.");\n            }
        }
        Intent intent = new Intent(context, AmikiWakeWordProvisioningService.class)
                .setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void cancel(Context context) {
        context.startService(new Intent(context, AmikiWakeWordProvisioningService.class)
                .setAction(ACTION_CANCEL));
    }

    static String state(Context context) {
        return preferences(context).getString(KEY_STATE, "IDLE");
    }

    static int progress(Context context) {
        return preferences(context).getInt(KEY_PROGRESS, 0);
    }

    static String detail(Context context) {
        return preferences(context).getString(KEY_DETAIL, "No provisioning session has been started.");
    }

    static String sessionId(Context context) {
        return preferences(context).getString(KEY_SESSION_ID, "");
    }

    static boolean isActive(Context context) {
        String value = state(context);
        return "STARTING".equals(value) || "RUNNING".equals(value) || "RETRYING".equals(value);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void save(String state, int progress, String detail) {
        int boundedProgress = Math.max(0, Math.min(100, progress));
        String safeDetail = detail == null ? "" : detail;
        SharedPreferences prefs = preferences(this);
        String previousState = prefs.getString(KEY_STATE, "IDLE");
        int previousProgress = prefs.getInt(KEY_PROGRESS, 0);
        String previousDetail = prefs.getString(KEY_DETAIL, "");
        if (state.equals(previousState) && boundedProgress == previousProgress
                && safeDetail.equals(previousDetail)) {
            return;
        }
        prefs.edit()
                .putString(KEY_STATE, state)
                .putInt(KEY_PROGRESS, boundedProgress)
                .putString(KEY_DETAIL, safeDetail)
                .putLong(KEY_UPDATED, System.currentTimeMillis())
                .apply();
        // Avoid notifying Android for every 16 KiB transfer chunk. Progress is
        // persisted at integer-percent checkpoints; notifications update at 5% steps.
        if (!state.equals(lastNotifiedState)
                || Math.abs(boundedProgress - lastNotifiedProgress) >= 5) {
            updateNotification(state, safeDetail);
            lastNotifiedState = state;
            lastNotifiedProgress = boundedProgress;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_CANCEL.equals(intent.getAction())) {
            cancelled = true;
            save("CANCELLED", progress(this), "Provisioning cancelled by owner.");
            if (executor != null) executor.shutdownNow();
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        if (!startForegroundSafely()) {
            save("FAILED", progress(this),
                    "Android did not allow the provisioning foreground service to start.");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        synchronized (lock) {
            if (!running) {
                running = true;
                cancelled = false;
                save("STARTING", 0, "Amiki is resuming the wake-word provisioning session…");
                executor.execute(this::runProvisioning);
            }
        }
        // Android may recreate the service with a null Intent after reclaiming
        // the process. The persisted session is resumed by the same path.
        return START_STICKY;
    }

    private void runProvisioning() {
        Exception lastFailure = null;
        for (int attempt = 1; attempt <= MAX_SESSION_ATTEMPTS && !cancelled; attempt++) {
            try {
                final int sessionAttempt = attempt;
                save(attempt == 1 ? "RUNNING" : "RETRYING",
                        progress(this),
                        attempt == 1
                                ? "Provisioning the local wake-word model…"
                                : "Recovering the Termux worker (attempt " + attempt
                                        + " of " + MAX_SESSION_ATTEMPTS + ")…");
                AmikiWakeWordModel.install(this, (percentage, detail) -> {
                    if (!cancelled) save("RUNNING", percentage, detail);
                });
                if (!cancelled) {
                    save("COMPLETE", 100,
                            "Hey Amiki's model is installed and SHA-256 verified.");
                    AmikiWakeWordService.clearLegacyPicovoiceData(this);
                }
                stopForeground(true);
                stopSelf();
                return;
            } catch (Exception failure) {
                lastFailure = failure;
                android.util.Log.w("AmikiWakeWordProvisioning",
                        "Provisioning attempt " + attempt + " failed.", failure);
                if (cancelled) break;
                if (attempt < MAX_SESSION_ATTEMPTS) {
                    save("RETRYING", progress(this),
                            "Provisioning was interrupted. Verified files are retained; "
                                    + "Amiki will retry (" + (attempt + 1) + " of "
                                    + MAX_SESSION_ATTEMPTS + ").");
                    try {
                        TimeUnit.SECONDS.sleep(Math.min(30L, attempt * 5L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        cancelled = true;
                        break;
                    }
                }
            }
        }

        if (cancelled) {
            save("CANCELLED", progress(this), "Provisioning cancelled.");
        } else {
            String message = lastFailure == null ? "Provisioning did not complete."
                    : lastFailure.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = lastFailure.getClass().getSimpleName();
            }
            save("FAILED", progress(this), "Provisioning stopped after recovery attempts. " + message);
        }
        stopForeground(true);
        stopSelf();
    }

    private boolean startForegroundSafely() {
        try {
            Notification notification = buildNotification("STARTING",
                    "Amiki is preparing the local wake-word model…");
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException failure) {
            android.util.Log.e("AmikiWakeWordProvisioning",
                    "Could not enter foreground state.", failure);
            return false;
        }
    }

    private Notification buildNotification(String state, String detail) {
        Intent open = new Intent(this, WakeWordSettingsActivity.class);
        PendingIntent content = PendingIntent.getActivity(this, 8401, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent cancel = new Intent(this, AmikiWakeWordProvisioningService.class)
                .setAction(ACTION_CANCEL);
        PendingIntent cancelAction = PendingIntent.getService(this, 8402, cancel,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Amiki wake-word setup")
                .setContentText(detail == null ? state : detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setOngoing("RUNNING".equals(state) || "RETRYING".equals(state)
                        || "STARTING".equals(state))
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(content)
                .addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_menu_close_clear_cancel,
                        "Cancel", cancelAction).build())
                .build();
    }

    private void updateNotification(String state, String detail) {
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null && ("RUNNING".equals(state) || "RETRYING".equals(state)
                || "STARTING".equals(state))) {
            manager.notify(NOTIFICATION_ID, buildNotification(state, detail));
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Wake-word model provisioning", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Shows progress while Amiki installs its local wake-word model.");
        channel.setShowBadge(false);
        NotificationManager manager =
                (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.createNotificationChannel(channel);
    }

    @Override
    public void onDestroy() {
        synchronized (lock) {
            if (executor != null) executor.shutdownNow();
            running = false;
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
