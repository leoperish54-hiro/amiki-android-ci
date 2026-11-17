package com.amiki.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded foreground runtime for an explicitly invoked Android assistant request.
 *
 * This service is started only after Android's assistant gesture and speech UI
 * have supplied a command. It is not an ambient microphone or wake-word service.
 * It reuses the normal Core chat endpoint and UOSA session; it does not create
 * an alternate privileged device-execution path.
 */
public final class AssistantCommandService extends Service {
    public static final String ACTION_HANDLE_COMMAND = "com.amiki.app.action.HANDLE_ASSISTANT_COMMAND";
    public static final String ACTION_STOP = "com.amiki.app.action.STOP_ASSISTANT_COMMAND";
    public static final String EXTRA_COMMAND = "com.amiki.app.extra.ASSISTANT_COMMAND";

    private static final String CHANNEL_ID = "amiki_assistant_runtime";
    private static final int NOTIFICATION_ID = 4071;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private NotificationManager notifications;
    private TextToSpeech speech;
    private boolean speechReady;
    private boolean requestRunning;
    private boolean destroyed;
    private String pendingSpeech;

    public static void start(Context context, String command) {
        if (context == null || command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("A non-empty assistant command is required.");
        }
        Intent intent = new Intent(context, AssistantCommandService.class);
        intent.setAction(ACTION_HANDLE_COMMAND);
        intent.putExtra(EXTRA_COMMAND, command);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, AssistantCommandService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createChannel();
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Amiki-Assistant-Command");
            thread.setDaemon(true);
            return thread;
        });
        speech = new TextToSpeech(getApplicationContext(), status -> {
            speechReady = status == TextToSpeech.SUCCESS;
            if (speechReady) {
                speech.setLanguage(Locale.getDefault());
                speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                    @Override public void onStart(String utteranceId) { }
                    @Override public void onDone(String utteranceId) {
                        main.post(() -> finishRuntime());
                    }
                    @Override public void onError(String utteranceId) {
                        main.post(() -> {
                            updateNotification("Speech output failed. No spoken response was delivered.");
                            main.postDelayed(() -> finishRuntime(), 8000L);
                        });
                    }
                });
                if (pendingSpeech != null) speak(pendingSpeech);
            } else {
                updateNotification("Speech output is unavailable. No spoken response was delivered.");
                main.postDelayed(() -> finishRuntime(), 10000L);
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            finishRuntime();
            return START_NOT_STICKY;
        }

        // Android requires a foreground notification immediately after a
        // startForegroundService request; do this before network or TTS work.
        startForeground(NOTIFICATION_ID, buildNotification("Working on your voice request…"));

        if (requestRunning) {
            updateNotification("Amiki is already handling a request. This extra request was not queued.");
            return START_NOT_STICKY;
        }

        String command = intent == null ? null : intent.getStringExtra(EXTRA_COMMAND);
        try {
            command = VoiceAssistantCommandValidator.validate(command);
        } catch (IllegalArgumentException invalid) {
            updateNotification(invalid.getMessage());
            main.postDelayed(() -> finishRuntime(), 5000L);
            return START_NOT_STICKY;
        }

        requestRunning = true;
        final String acceptedCommand = command;
        executor.execute(() -> {
            try {
                String answer = askCore(acceptedCommand);
                main.post(() -> {
                    updateNotification("Amiki has prepared a response. Speaking it now…");
                    pendingSpeech = answer;
                    if (speechReady) speak(answer);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    String detail = failure.getMessage();
                    updateNotification(detail == null
                            ? "Amiki could not reach Core. No device action was executed."
                            : "Amiki could not complete the request: " + bounded(detail, 180));
                    main.postDelayed(() -> finishRuntime(), 12000L);
                });
            }
        });
        return START_NOT_STICKY;
    }

    private String askCore(String message) throws Exception {
        awaitCoreReady();
        String conversationId = voiceConversationId();
        ensureConversationExists(conversationId);

        JSONObject request = new JSONObject();
        request.put("message", message);
        request.put("request_id", UUID.randomUUID().toString());
        request.put("conversation_id", conversationId);

        HttpURLConnection connection = ApiConnection.open(this, ApiEndpoints.CHAT);
        try {
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(120000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(request.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readLimited(stream, MAX_RESPONSE_BYTES);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Core returned HTTP " + status + ".");
            }
            JSONObject response = new JSONObject(body);
            String answer = response.optString("response", "").trim();
            if (answer.isEmpty()) throw new java.io.IOException("Core returned an empty response.");
            return bounded(answer, 3500);
        } finally {
            connection.disconnect();
        }
    }

    private void awaitCoreReady() throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            HttpURLConnection connection = null;
            try {
                connection = ApiConnection.open(this, ApiEndpoints.HEALTH);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(1500);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) return;
                lastFailure = new java.io.IOException("Core health endpoint returned HTTP " + status + ".");
            } catch (Exception failure) {
                lastFailure = failure;
            } finally {
                if (connection != null) connection.disconnect();
            }
            if (attempt < 5) {
                try {
                    Thread.sleep(700L);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("Assistant request was stopped.", interrupted);
                }
            }
        }
        throw new java.io.IOException("Amiki Core did not become ready. No device action was executed.", lastFailure);
    }

    private String voiceConversationId() {
        SharedPreferences preferences = getSharedPreferences("amiki_assistant_voice", MODE_PRIVATE);
        String existing = preferences.getString("conversation_id", null);
        if (existing != null) {
            try {
                UUID parsed = UUID.fromString(existing);
                if (parsed.version() == 4 && parsed.toString().equalsIgnoreCase(existing)) {
                    return parsed.toString();
                }
            } catch (IllegalArgumentException ignored) {
                // Replace malformed local metadata with a fresh random ID.
            }
        }
        String created = UUID.randomUUID().toString();
        preferences.edit().putString("conversation_id", created).apply();
        return created;
    }

    private void ensureConversationExists(String conversationId) throws Exception {
        HttpURLConnection lookup = ApiConnection.open(this, ApiEndpoints.conversation(conversationId));
        int status;
        try {
            lookup.setRequestMethod("GET");
            lookup.setConnectTimeout(7000);
            lookup.setReadTimeout(10000);
            status = lookup.getResponseCode();
        } finally {
            lookup.disconnect();
        }
        if (status == HttpURLConnection.HTTP_OK) return;
        if (status != HttpURLConnection.HTTP_NOT_FOUND) {
            throw new java.io.IOException("Core conversation lookup returned HTTP " + status + ".");
        }

        JSONObject createBody = new JSONObject();
        createBody.put("conversation_id", conversationId);
        createBody.put("title", "Voice Assistant");
        HttpURLConnection create = ApiConnection.open(this, ApiEndpoints.CONVERSATIONS);
        try {
            create.setRequestMethod("POST");
            create.setConnectTimeout(7000);
            create.setReadTimeout(10000);
            create.setDoOutput(true);
            create.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = create.getOutputStream()) {
                output.write(createBody.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = create.getResponseCode();
            if (code != HttpURLConnection.HTTP_CREATED && code != HttpURLConnection.HTTP_CONFLICT) {
                throw new java.io.IOException("Core conversation registration returned HTTP " + code + ".");
            }
        } finally {
            create.disconnect();
        }
    }

    private void speak(String text) {
        if (destroyed || speech == null || !speechReady) {
            pendingSpeech = text;
            return;
        }
        pendingSpeech = null;
        int result = speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "amiki_assistant_response");
        if (result == TextToSpeech.ERROR) {
            updateNotification("Speech output could not start. No spoken response was delivered.");
            main.postDelayed(() -> finishRuntime(), 10000L);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && notifications != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Amiki voice requests", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Visible status and stop control for explicitly invoked assistant requests.");
            notifications.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String detail) {
        Intent stop = new Intent(this, AssistantCommandService.class);
        stop.setAction(ACTION_STOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent stopIntent = PendingIntent.getService(this, 4072, stop, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Amiki voice request")
                .setContentText(bounded(detail, 180))
                .setStyle(new Notification.BigTextStyle().bigText(bounded(detail, 500)))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent);
        return builder.build();
    }

    private void updateNotification(String detail) {
        if (notifications != null) notifications.notify(NOTIFICATION_ID, buildNotification(detail));
    }

    private void finishRuntime() {
        if (destroyed) return;
        stopForeground(true);
        stopSelf();
    }

    private static String readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new java.io.IOException("Core response exceeded the size limit.");
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private static String bounded(String value, int maxChars) {
        if (value == null) return "";
        String normalized = value.trim();
        return normalized.length() <= maxChars
                ? normalized : normalized.substring(0, maxChars) + "…";
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        if (executor != null) executor.shutdownNow();
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
