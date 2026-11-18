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
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import ai.picovoice.porcupine.PorcupineManager;
import ai.picovoice.porcupine.PorcupineManagerCallback;
import ai.picovoice.porcupine.PorcupineManagerErrorCallback;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Owner-enabled local keyword detector. Detection is on-device; the AccessKey
 * is used for model provisioning and validation, not for streaming standby
 * microphone audio to a remote speech recognizer.
 *
 * This service is started only from a visible settings activity after explicit
 * opt-in. It is never started from BOOT_COMPLETED.
 */
public final class AmikiWakeWordService extends Service {
    public static final String ACTION_ENABLE = "com.amiki.app.action.ENABLE_WAKE_WORD";
    public static final String ACTION_PAUSE = "com.amiki.app.action.PAUSE_WAKE_WORD";
    public static final String ACTION_RESUME = "com.amiki.app.action.RESUME_WAKE_WORD";
    public static final String ACTION_DISABLE = "com.amiki.app.action.DISABLE_WAKE_WORD";

    public static final String SECRET_ACCESS_KEY = "picovoice_access_key";

    private static final String PREFS = "amiki_wake_word";
    private static final String KEY_ENABLED = "enabled";
    private static final String CHANNEL_ID = "amiki_wake_word";
    private static final int NOTIFICATION_ID = 4091;

    private static volatile AmikiWakeWordService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object managerLock = new Object();
    private ExecutorService initializer;
    private PorcupineManager porcupineManager;
    private volatile boolean paused = true;
    private volatile boolean initializing;
    private volatile long generation;

    public static File modelFile(Context context) {
        return new File(context.getFilesDir(), "hey_amiki.ppn");
    }

    public static boolean isEnabled(Context context) {
        return context != null && context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, false);
    }

    private static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isConfigured(Context context) {
        if (context == null || !modelFile(context).isFile()) return false;
        try {
            String key = AmikiSecretStore.get(context, SECRET_ACCESS_KEY);
            return key != null && !key.trim().isEmpty();
        } catch (Exception unreadable) {
            return false;
        }
    }

    public static void enableAndStart(Context context) {
        if (context == null) throw new IllegalArgumentException("A context is required.");
        if (!isConfigured(context)) {
            throw new IllegalStateException("Save a Picovoice AccessKey and create the Hey Amiki model first.");
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

    public static void disableAndStop(Context context) {
        if (context == null) return;
        setEnabled(context, false);
        Intent intent = new Intent(context, AmikiWakeWordService.class).setAction(ACTION_DISABLE);
        context.startService(intent);
    }

    /** Synchronously release the microphone before explicit assistant STT starts. */
    public static void pauseForAssistant() {
        AmikiWakeWordService service = instance;
        if (service != null) service.pauseListening("Paused while Amiki handles your request");
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
        return service != null && !service.paused && service.porcupineManager != null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        initializer = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Amiki-Hey-Amiki-Wake");
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
            updateNotification("Hey Amiki is not configured. Open Amiki settings to finish setup.");
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        startWakeForeground();
        if (ACTION_PAUSE.equals(action)) {
            pauseListening("Paused while Amiki handles your request");
        } else {
            resumeListening();
        }
        return START_STICKY;
    }

    private void startWakeForeground() {
        Notification notification = buildNotification("Hey Amiki is ready for its local wake phrase");
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
        synchronized (managerLock) {
            if (!paused || initializing) return;
            paused = false;
            initializing = true;
        }
        final long token = ++generation;
        updateNotification("Starting local Hey Amiki detection…");
        initializer.execute(() -> {
            PorcupineManager created = null;
            try {
                String accessKey = AmikiSecretStore.get(this, SECRET_ACCESS_KEY);
                File model = modelFile(this);
                if (accessKey == null || !model.isFile()) {
                    throw new IllegalStateException("Wake-word credentials or model are unavailable.");
                }

                created = new PorcupineManager.Builder()
                        .setAccessKey(accessKey)
                        .setKeywordPath(model.getAbsolutePath())
                        .setSensitivity(0.55f)
                        .setErrorCallback(new PorcupineManagerErrorCallback() {
                            @Override
                            public void invoke(ai.picovoice.porcupine.PorcupineException error) {
                                main.post(() -> handleWakeError(error));
                            }
                        })
                        .build(getApplicationContext(), new PorcupineManagerCallback() {
                            @Override
                            public void invoke(int keywordIndex) {
                                if (keywordIndex == 0) main.post(AmikiWakeWordService.this::onWakeWordDetected);
                            }
                        });

                synchronized (managerLock) {
                    initializing = false;
                    if (paused || token != generation || !isEnabled(AmikiWakeWordService.this)) {
                        created.delete();
                        return;
                    }
                    porcupineManager = created;
                    created.start();
                }
                updateNotification("Listening locally for “Hey Amiki”");
            } catch (Exception failure) {
                synchronized (managerLock) {
                    initializing = false;
                    paused = true;
                    if (porcupineManager != null) {
                        releaseManager(porcupineManager);
                        porcupineManager = null;
                    }
                }
                if (created != null) releaseManager(created);
                updateNotification("Wake-word engine could not start. Check model, AccessKey and microphone permission.");
            }
        });
    }

    private void onWakeWordDetected() {
        if (paused || !isEnabled(this)) return;
        pauseListening("Hey Amiki detected — opening assistant");
        if (!AmikiVoiceInteractionService.showAssistantSession()) {
            updateNotification("Hey Amiki detected, but Amiki is not the active Android voice assistant.");
        }
    }

    private void handleWakeError(Exception error) {
        pauseListening("Wake-word engine error. Open Amiki settings to retry.");
    }

    private void pauseListening(String status) {
        synchronized (managerLock) {
            generation++;
            paused = true;
            initializing = false;
            PorcupineManager current = porcupineManager;
            porcupineManager = null;
            if (current != null) releaseManager(current);
        }
        updateNotification(status);
    }

    private static void releaseManager(PorcupineManager manager) {
        if (manager == null) return;
        try { manager.stop(); } catch (Exception ignored) { }
        try { manager.delete(); } catch (RuntimeException ignored) { }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) return;
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Hey Amiki wake word", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Visible status and Stop control while local wake-word detection is enabled.");
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
