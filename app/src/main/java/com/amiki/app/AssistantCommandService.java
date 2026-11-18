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
import java.util.concurrent.Future;

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
    public static final String ACTION_HANDLE_DEVICE_TASK = "com.amiki.app.action.HANDLE_DEVICE_TASK";
    public static final String ACTION_STOP = "com.amiki.app.action.STOP_ASSISTANT_COMMAND";
    public static final String EXTRA_COMMAND = "com.amiki.app.extra.ASSISTANT_COMMAND";
    public static final String EXTRA_DEVICE_TASK = "com.amiki.app.extra.DEVICE_TASK";

    private static final String CHANNEL_ID = "amiki_assistant_runtime";
    private static final int NOTIFICATION_ID = 4071;
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;

    private static volatile AssistantCommandService activeInstance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AmikiVoiceSession voiceSession = new AmikiVoiceSession();
    private ExecutorService executor;
    private Future<?> currentWork;
    private volatile HttpURLConnection activeConnection;
    private NotificationManager notifications;
    private TextToSpeech speech;
    private volatile boolean speechReady;
    private volatile boolean requestRunning;
    private volatile boolean destroyed;
    private String pendingSpeech;
    private long pendingSpeechTurn;
    private volatile long activeTurnGeneration;

    private static final String TTS_UTTERANCE_PREFIX = "amiki_voice_turn_";

    public static boolean isBusy() {
        AssistantCommandService service = activeInstance;
        return service != null && service.requestRunning;
    }

    private boolean isCurrentTurn(long turnId) {
        return turnId > 0L && activeTurnGeneration == turnId
                && voiceSession.isCurrent(turnId) && !destroyed;
    }

    private synchronized long beginTurn() {
        long turnId = voiceSession.beginCommand();
        activeTurnGeneration = turnId;
        requestRunning = true;
        return turnId;
    }

    /** Invalidate stale callbacks, disconnect HTTP, stop speech and cancel UI work. */
    private synchronized void cancelActiveTurn() {
        voiceSession.stop();
        activeTurnGeneration = 0L;
        requestRunning = false;
        pendingSpeech = null;
        pendingSpeechTurn = 0L;
        Future<?> work = currentWork;
        currentWork = null;
        if (work != null) work.cancel(true);
        HttpURLConnection connection = activeConnection;
        activeConnection = null;
        if (connection != null) connection.disconnect();
        if (speech != null) {
            try { speech.stop(); } catch (RuntimeException ignored) { }
        }
        if (AccessibilityOperatorService.isObservationArmed()) {
            AccessibilityOperatorService.stopObservation();
        }
    }

    private void trackConnection(long turnId, HttpURLConnection connection) throws Exception {
        if (!isCurrentTurn(turnId)) {
            connection.disconnect();
            throw new java.io.IOException("Assistant request was superseded.");
        }
        activeConnection = connection;
    }

    private void clearConnection(HttpURLConnection connection) {
        if (activeConnection == connection) activeConnection = null;
    }

    private void finishRuntimeForTurn(long turnId) {
        if (!isCurrentTurn(turnId)) return;
        requestRunning = false;
        activeTurnGeneration = 0L;
        voiceSession.finishResponse(turnId);
        if (AccessibilityOperatorService.isObservationArmed()) {
            AccessibilityOperatorService.stopObservation();
        }
        stopForeground(true);
        AmikiWakeWordService.resumeIfEnabled();
        stopSelf();
    }

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

    public static void requestStop(Context context) {
        if (context == null) return;
        Intent intent = new Intent(context, AssistantCommandService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
        AccessibilityOperatorService.stopObservation();
    }

    public static void startDeviceTask(Context context, String command) {
        if (context == null || command == null || command.trim().isEmpty()) {
            throw new IllegalArgumentException("A non-empty device task is required.");
        }
        Intent intent = new Intent(context, AssistantCommandService.class);
        intent.setAction(ACTION_HANDLE_DEVICE_TASK);
        intent.putExtra(EXTRA_DEVICE_TASK, command);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        activeInstance = this;
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
                    @Override public void onStart(String utteranceId) {
                        long turnId = turnFromUtteranceId(utteranceId);
                        if (turnId > 0L && isCurrentTurn(turnId)) {
                            voiceSession.beginResponse(turnId);
                            updateNotification("Amiki is speaking. Say a new command to start another turn.");
                        }
                    }

                    @Override public void onDone(String utteranceId) {
                        long turnId = turnFromUtteranceId(utteranceId);
                        main.post(() -> finishRuntimeForTurn(turnId));
                    }

                    @Override public void onError(String utteranceId) {
                        long turnId = turnFromUtteranceId(utteranceId);
                        main.post(() -> {
                            if (!isCurrentTurn(turnId)) return;
                            updateNotification("Speech output failed. No spoken response was delivered.");
                            main.postDelayed(() -> finishRuntimeForTurn(turnId), 5000L);
                        });
                    }
                });
                if (pendingSpeech != null && isCurrentTurn(pendingSpeechTurn)) {
                    speak(pendingSpeech, pendingSpeechTurn);
                }
            } else {
                updateNotification("Speech output is unavailable. The foreground task runtime will remain visible until it completes or is stopped.");
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            cancelActiveTurn();
            finishRuntime();
            return START_NOT_STICKY;
        }

        // Android requires a foreground notification immediately after a
        // startForegroundService request; do this before network or TTS work.
        startForeground(NOTIFICATION_ID, buildNotification("Working on your voice request…"));

        // A new explicit invocation supersedes the previous turn. This is the
        // Jarvis turn-generation rule: older network/TTS callbacks cannot win.
        if (requestRunning) cancelActiveTurn();

        boolean deviceTask = intent != null
                && ACTION_HANDLE_DEVICE_TASK.equals(intent.getAction());
        String command = intent == null ? null : intent.getStringExtra(
                deviceTask ? EXTRA_DEVICE_TASK : EXTRA_COMMAND);
        try {
            command = VoiceAssistantCommandValidator.validate(command);
        } catch (IllegalArgumentException invalid) {
            updateNotification(invalid.getMessage());
            main.postDelayed(() -> {
                if (!requestRunning) finishRuntime();
            }, 5000L);
            return START_NOT_STICKY;
        }

        AmikiWakeWordService.pauseForAssistant();
        final long turnId = beginTurn();
        final String acceptedCommand = command;

        if (deviceTask) {
            currentWork = executor.submit(() -> {
                try {
                    awaitCoreReady(turnId);
                    if (!isCurrentTurn(turnId)) return;
                    main.post(() -> {
                        if (isCurrentTurn(turnId)) startDeviceTaskRuntime(acceptedCommand, turnId);
                    });
                } catch (Exception failure) {
                    main.post(() -> {
                        if (!isCurrentTurn(turnId)) return;
                        String detail = failure.getMessage();
                        updateNotification(detail == null
                                ? "Amiki Core is unavailable. No device action was executed."
                                : bounded(detail, 180));
                        pendingSpeech = "Amiki could not start the device task. " + bounded(detail, 180);
                        pendingSpeechTurn = turnId;
                        if (speechReady) speak(pendingSpeech, turnId);
                        else main.postDelayed(() -> finishRuntimeForTurn(turnId), 10000L);
                    });
                }
            });
            return START_NOT_STICKY;
        }

        currentWork = executor.submit(() -> {
            try {
                String answer = askCore(acceptedCommand, turnId);
                main.post(() -> {
                    if (!isCurrentTurn(turnId)) return;
                    updateNotification("Amiki has prepared a response. Speaking it now…");
                    pendingSpeech = answer;
                    pendingSpeechTurn = turnId;
                    if (speechReady) speak(answer, turnId);
                    else main.postDelayed(() -> finishRuntimeForTurn(turnId), 10000L);
                });
            } catch (Exception failure) {
                main.post(() -> {
                    if (!isCurrentTurn(turnId)) return;
                    String detail = failure.getMessage();
                    updateNotification(detail == null
                            ? "Amiki could not reach Core. No device action was executed."
                            : "Amiki could not complete the request: " + bounded(detail, 180));
                    main.postDelayed(() -> finishRuntimeForTurn(turnId), 12000L);
                });
            }
        });
        return START_NOT_STICKY;
    }

    private void startDeviceTaskRuntime(String command, long turnId) {
        if (!isCurrentTurn(turnId)) return;
        try {
            AccessibilityOperatorService.startUserTask(this, command,
                    new DeviceTaskOrchestrator.Listener() {
                        @Override
                        public void onStatus(String message) {
                            if (isCurrentTurn(turnId)) updateNotification(message);
                        }

                        @Override
                        public void onFinished(String message, boolean needsOwnerInput) {
                            if (!isCurrentTurn(turnId)) return;
                            updateNotification(message);
                            pendingSpeech = message;
                            pendingSpeechTurn = turnId;
                            if (speechReady) speak(message, turnId);
                            else main.postDelayed(() -> finishRuntimeForTurn(turnId), 10000L);
                        }
                    });
        } catch (RuntimeException failure) {
            if (!isCurrentTurn(turnId)) return;
            String detail = failure.getMessage();
            String message = detail == null
                    ? "Amiki could not start the Android UI Operator. No device action was performed."
                    : bounded(detail, 220);
            updateNotification(message);
            pendingSpeech = message;
            pendingSpeechTurn = turnId;
            if (speechReady) speak(message, turnId);
            else main.postDelayed(() -> finishRuntimeForTurn(turnId), 10000L);
        }
    }

    private String askCore(String message, long turnId) throws Exception {
        awaitCoreReady(turnId);
        ensureTurnActive(turnId);
        String conversationId = voiceConversationId();
        ensureConversationExists(conversationId, turnId);

        JSONObject request = new JSONObject();
        request.put("message", message);
        request.put("request_id", UUID.randomUUID().toString());
        request.put("conversation_id", conversationId);

        HttpURLConnection connection = ApiConnection.open(this, ApiEndpoints.CHAT);
        trackConnection(turnId, connection);
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
            ensureTurnActive(turnId);
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = stream == null ? "" : readLimited(stream, MAX_RESPONSE_BYTES);
            ensureTurnActive(turnId);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Core returned HTTP " + status + ".");
            }
            JSONObject response = new JSONObject(body);
            String answer = response.optString("response", "").trim();
            if (answer.isEmpty()) throw new java.io.IOException("Core returned an empty response.");
            return bounded(answer, 3500);
        } finally {
            clearConnection(connection);
            connection.disconnect();
        }
    }

    private void ensureTurnActive(long turnId) throws java.io.IOException {
        if (Thread.currentThread().isInterrupted() || !isCurrentTurn(turnId)) {
            throw new java.io.IOException("Assistant request was superseded or stopped.");
        }
    }

    private void awaitCoreReady(long turnId) throws Exception {
        Exception lastFailure = null;
        for (int attempt = 0; attempt < 6; attempt++) {
            ensureTurnActive(turnId);
            HttpURLConnection connection = null;
            try {
                connection = ApiConnection.open(this, ApiEndpoints.HEALTH);
                trackConnection(turnId, connection);
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(1500);
                connection.setReadTimeout(1500);
                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) return;
                lastFailure = new java.io.IOException("Core health endpoint returned HTTP " + status + ".");
            } catch (Exception failure) {
                if (!isCurrentTurn(turnId)) throw failure;
                lastFailure = failure;
            } finally {
                if (connection != null) {
                    clearConnection(connection);
                    connection.disconnect();
                }
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

    private void ensureConversationExists(String conversationId, long turnId) throws Exception {
        HttpURLConnection lookup = ApiConnection.open(this, ApiEndpoints.conversation(conversationId));
        trackConnection(turnId, lookup);
        int status;
        try {
            lookup.setRequestMethod("GET");
            lookup.setConnectTimeout(7000);
            lookup.setReadTimeout(10000);
            status = lookup.getResponseCode();
            ensureTurnActive(turnId);
        } finally {
            clearConnection(lookup);
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
        trackConnection(turnId, create);
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
            ensureTurnActive(turnId);
            if (code != HttpURLConnection.HTTP_CREATED && code != HttpURLConnection.HTTP_CONFLICT) {
                throw new java.io.IOException("Core conversation registration returned HTTP " + code + ".");
            }
        } finally {
            clearConnection(create);
            create.disconnect();
        }
    }

    private void speak(String text, long turnId) {
        if (!isCurrentTurn(turnId)) return;
        if (destroyed || speech == null || !speechReady) {
            pendingSpeech = text;
            pendingSpeechTurn = turnId;
            return;
        }
        if (!voiceSession.beginResponse(turnId)) return;
        pendingSpeech = null;
        pendingSpeechTurn = 0L;
        String utteranceId = TTS_UTTERANCE_PREFIX + turnId;
        int result = speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId);
        if (result == TextToSpeech.ERROR) {
            updateNotification("Speech output could not start. No spoken response was delivered.");
            main.postDelayed(() -> finishRuntimeForTurn(turnId), 10000L);
        }
    }

    private long turnFromUtteranceId(String utteranceId) {
        if (utteranceId == null || !utteranceId.startsWith(TTS_UTTERANCE_PREFIX)) return -1L;
        try {
            return Long.parseLong(utteranceId.substring(TTS_UTTERANCE_PREFIX.length()));
        } catch (NumberFormatException invalid) {
            return -1L;
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
        cancelActiveTurn();
        if (AccessibilityOperatorService.isObservationArmed()) {
            AccessibilityOperatorService.stopObservation();
        }
        stopForeground(true);
        AmikiWakeWordService.resumeIfEnabled();
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
        if (requestRunning) cancelActiveTurn();
        if (AccessibilityOperatorService.isObservationArmed()) {
            AccessibilityOperatorService.stopObservation();
        }
        destroyed = true;
        voiceSession.stop();
        if (activeConnection != null) {
            activeConnection.disconnect();
            activeConnection = null;
        }
        if (executor != null) executor.shutdownNow();
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
        if (notifications != null) notifications.cancel(NOTIFICATION_ID);
        if (activeInstance == this) activeInstance = null;
        AmikiWakeWordService.resumeIfEnabled();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
