package com.amiki.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner-enabled foreground service for Amiki's on-device "Hey Amiki" detector.
 *
 * The model is downloaded and SHA-256 verified before activation. The service
 * never streams standby audio to a network recognizer and is never launched
 * from BOOT_COMPLETED. It pauses and releases the microphone before an
 * assistant request begins.
 */
public final class AmikiWakeWordService extends Service {
    public static final String ACTION_ENABLE = "com.amiki.app.action.ENABLE_WAKE_WORD";
    public static final String ACTION_PAUSE = "com.amiki.app.action.PAUSE_WAKE_WORD";
    public static final String ACTION_RESUME = "com.amiki.app.action.RESUME_WAKE_WORD";
    public static final String ACTION_DISABLE = "com.amiki.app.action.DISABLE_WAKE_WORD";

    private static final String PREFS = "amiki_wake_word";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "amiki_wake_word";
    private static final int NOTIFICATION_ID = 4091;
    private static final String LEGACY_PICOVOICE_SECRET = "picovoice_access_key";

    private static volatile AmikiWakeWordService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object engineLock = new Object();
    private ExecutorService initializer;
    private AmikiWakeWordEngine wakeWordEngine;
    private volatile boolean paused = true;
    private volatile boolean initializing;
    private volatile long generation;

    public static boolean isEnabled(Context context) {
        return context != null && context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    private static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isConfigured(Context context) {
        return AmikiWakeWordModel.isInstalled(context);
    }

    /** Remove only the obsolete Picovoice wake-word files after model migration. */
    static void clearLegacyPicovoiceData(Context context) {
        if (context == null) return;
        AmikiSecretStore.remove(context, LEGACY_PICOVOICE_SECRET);
        File legacyModel = new File(context.getFilesDir(), "hey_amiki.ppn");
        if (legacyModel.exists()) legacyModel.delete();
    }

    public static void enableAndStart(Context context) {
        if (context == null) throw new IllegalArgumentException("A context is required.");
        if (!isConfigured(context)) {
            throw new IllegalStateException("Install the verified Amiki wake-word model first.");
        }
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Microphone permission is required.");
        }
        setEnabled(context, true);
        Intent intent = new Intent(context, AmikiWakeWordService.class).setAction(ACTION_ENABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
        else context.startService(intent);
    }

    /**
     * Restore only a previously owner-enabled listener, and only while the
     * system-selected VoiceInteractionService and required permissions exist.
     */
    public static void startIfEnabledFromVoiceInteraction(Context context) {
        if (context == null || !isEnabled(context) || !isConfigured(context)) return;
        if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        AmikiWakeWordService running = instance;
        if (running != null) {
            running.resumeListening();
            return;
        }
        Intent intent = new Intent(context, AmikiWakeWordService.class).setAction(ACTION_RESUME);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent);
            else context.startService(intent);
        } catch (RuntimeException backgroundStartDenied) {
            // Do not crash Android's system-managed VoiceInteractionService.
            // The owner can reopen Hey Amiki settings and enable listening while visible.
        }
    }

    public static void disableAndStop(Context context) {
        if (context == null) return;
        setEnabled(context, false);
        Intent intent = new Intent(context, AmikiWakeWordService.class).setAction(ACTION_DISABLE);
        context.startService(intent);
    }

    /** Synchronously release the microphone before explicit assistant STT starts. */
    public static void pauseForAssistant() {
        AmikiWakeWordService service = instance;
        if (service != null) {
            service.pauseListening("Paused while Amiki handles your request");
        }
    }

    /** Resume the already-running foreground service after an assistant turn. */
    public static void resumeIfEnabled() {
        AmikiWakeWordService service = instance;
        if (service != null && isEnabled(service)) service.resumeListening();
    }

    public static boolean isServiceRunning() {
        return instance != null;
    }

    public static boolean isListening() {
        AmikiWakeWordService service = instance;
        if (service == null || service.paused) return false;
        AmikiWakeWordEngine engine = service.wakeWordEngine;
        return engine != null && engine.isListening();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initializer = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Amiki-Hey-Amiki-Init");
            thread.setDaemon(true);
            return thread;
        });
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_DISABLE.equals(action)) {
            setEnabled(this, false);
            pauseListening("Wake word disabled");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_PAUSE.equals(action)) {
            pauseListening("Paused while Amiki handles your request");
            return START_STICKY;
        }
        if (ACTION_ENABLE.equals(action)) setEnabled(this, true);

        if (!isEnabled(this) || !isConfigured(this)) {
            updateNotification("Hey Amiki is not installed. Open its settings to install the local model.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startWakeForeground();
        resumeListening();
        return START_STICKY;
    }

    private void startWakeForeground() {
        Notification notification = buildNotification("Hey Amiki is ready for local keyword detection");
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            // Android 10/API 29 has no microphone foreground-service type.
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void resumeListening() {
        if (!isEnabled(this) || !isConfigured(this)) return;
        final long token;
        synchronized (engineLock) {
            if (!paused || initializing) return;
            paused = false;
            initializing = true;
            token = ++generation;
        }

        updateNotification("Loading Amiki's local Hey Amiki detector…");
        try {
            initializer.execute(() -> {
                AmikiWakeWordEngine created = null;
                try {
                    created = AmikiWakeWordEngine.create(getApplicationContext());
                    synchronized (engineLock) {
                        initializing = false;
                        if (paused || token != generation || !isEnabled(this)) {
                            created.close();
                            return;
                        }
                        wakeWordEngine = created;
                        created.start(new AmikiWakeWordEngine.Listener() {
                            @Override
                            public void onWakeWordDetected() {
                                main.post(AmikiWakeWordService.this::onWakeWordDetected);
                            }

                            @Override
                            public void onEngineError(Exception error) {
                                main.post(() -> handleWakeError(error));
                            }
                        });
                    }
                    updateNotification("Listening locally for “Hey Amiki”");
                } catch (Exception | LinkageError | OutOfMemoryError failure) {
                    synchronized (engineLock) {
                        if (token == generation) {
                            initializing = false;
                            paused = true;
                            if (wakeWordEngine == created) wakeWordEngine = null;
                        }
                    }
                    if (created != null) created.close();
                    android.util.Log.w("AmikiWakeWord", "Local wake-word engine could not start.", failure);
                    updateNotification("Local wake-word engine could not start. Open Hey Amiki settings to retry.");
                }
            });
        } catch (RuntimeException rejected) {
            synchronized (engineLock) {
                if (token == generation) {
                    initializing = false;
                    paused = true;
                }
            }
            updateNotification("Local wake-word engine is unavailable. Reopen Hey Amiki settings to retry.");
        }
    }

    private void onWakeWordDetected() {
        if (paused || !isEnabled(this)) return;
        pauseListening("Hey Amiki detected — opening assistant");
        if (!AmikiVoiceInteractionService.showAssistantSession()) {
            updateNotification("Hey Amiki detected, but Amiki is not the active Android voice assistant.");
        }
    }

    private void handleWakeError(Exception error) {
        android.util.Log.w("AmikiWakeWord", "Local wake-word capture stopped.", error);
        pauseListening("Wake-word engine stopped. Open Hey Amiki settings to retry.");
    }

    private void pauseListening(String status) {
        AmikiWakeWordEngine current;
        synchronized (engineLock) {
            generation++;
            paused = true;
            initializing = false;
            current = wakeWordEngine;
            wakeWordEngine = null;
        }
        if (current != null) current.close();
        updateNotification(status);
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Hey Amiki wake word", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription(
                    "Visible status and Stop control while local wake-word detection is enabled.");
            manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String detail) {
        Intent stop = new Intent(this, AmikiWakeWordService.class).setAction(ACTION_DISABLE);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopPending = PendingIntent.getService(this, 4092, stop, pendingFlags);
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Amiki — Hey Amiki")
                .setContentText(detail)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop listening", stopPending)
                .build();
    }

    private void updateNotification(String detail) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager != null && instance == this) {
            manager.notify(NOTIFICATION_ID, buildNotification(detail));
        }
    }

    @Override
    public void onDestroy() {
        pauseListening("Hey Amiki stopped");
        if (initializer != null) initializer.shutdownNow();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
