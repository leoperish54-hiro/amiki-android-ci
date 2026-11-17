package com.amiki.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Small non-AI Android observer for Amiki backend availability and bounded recovery.
 * It is intentionally independent of the backend so it can observe backend death.
 */
public final class SentinelService extends Service {
    private static final String CHANNEL_ID = "amiki_sentinel";
    private static final int NOTIFICATION_ID = 4011;
    private static final String HEALTH_URL = ApiEndpoints.HEALTH;
    private static final String SYNC_URL = ApiEndpoints.OBSERVABILITY_SENTINEL_EVENTS;
    private static final long POLL_INTERVAL_SECONDS = 15L;
    private static final int SYNC_BATCH_SIZE = 50;

    private ScheduledExecutorService executor;
    private SentinelJournal journal;
    private SentinelStateMachine stateMachine;
    private volatile boolean started;

    public static void start(Context context) {
        Intent intent = new Intent(context, SentinelService.class);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException deniedOrUnavailable) {
            // Keep a durable local explanation when Android/OEM policy refuses
            // foreground-service startup, so the failure is visible in Diagnostics.
            new SentinelJournal(context).append(
                    "RECOVERY_FAILED", "WARNING", "sentinel_startup", "failed",
                    "Android refused to start Sentinel. Check background and foreground-service permissions.",
                    null
            );
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        journal = new SentinelJournal(this);
        stateMachine = new SentinelStateMachine();
        createNotificationChannel();

        if (!startForegroundSafely()) {
            journal.append(
                    "RECOVERY_FAILED", "WARNING", "sentinel_startup", "failed",
                    "Android refused Sentinel foreground execution. Check foreground-service permissions.",
                    null
            );
            stopSelf();
            return;
        }
        record(
                "SENTINEL_STARTED",
                "INFO",
                "started",
                "Sentinel service started.",
                null
        );

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "amiki-sentinel");
            thread.setDaemon(true);
            return thread;
        });
        started = true;
        executor.scheduleWithFixedDelay(
                this::pollSafely,
                0L,
                POLL_INTERVAL_SECONDS,
                TimeUnit.SECONDS
        );
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        updateNotification();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        started = false;
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        record(
                "SENTINEL_STOPPED",
                "INFO",
                "stopped",
                "Sentinel service stopped.",
                null
        );
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollSafely() {
        if (!started) return;

        try {
            HealthCheck check = healthCheck();
            SentinelStateMachine.Transition transition =
                    check.healthy
                            ? stateMachine.onHealthSuccess()
                            : stateMachine.onHealthFailure();

            emitTransition(transition, check.httpStatus);

            if (transition.shouldAttemptRecovery()) {
                SentinelStateMachine.Transition recovery =
                        stateMachine.startRecovery();
                emitTransition(recovery, null);

                if (recovery.getEventType()
                        == SentinelStateMachine.EventType.RECOVERY_STARTED) {
                    boolean commandAccepted = startBackendRecovery();
                    SentinelStateMachine.Transition result =
                            stateMachine.onRecoveryCommandResult(commandAccepted);
                    emitTransition(result, null);
                }
            }

            if (check.healthy) {
                syncUnsyncedEvents();
            }

            journal.prune();
            updateNotification();
        } catch (Exception ignored) {
            // Sentinel must remain non-fatal even when its own observer path fails.
        }
    }

    private HealthCheck healthCheck() {
        HttpURLConnection connection = null;
        try {
            connection = ApiConnection.open(this, HEALTH_URL);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(2500);
            int status = connection.getResponseCode();
            return new HealthCheck(status == HttpURLConnection.HTTP_OK, status);
        } catch (Exception ignored) {
            return new HealthCheck(false, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private boolean startBackendRecovery() {
        // The exact Termux package/service/path contract lives in one helper shared
        // with BootstrapActivity. A denied or unavailable service returns false.
        return TermuxBackendLauncher.start(this);
    }

    private void emitTransition(
            SentinelStateMachine.Transition transition,
            Integer httpStatus
    ) {
        SentinelStateMachine.EventType type = transition.getEventType();
        if (type == SentinelStateMachine.EventType.NONE) {
            return;
        }

        String eventType;
        String severity;
        String status;
        String summary;

        switch (type) {
            case API_HEALTH_CHANGED:
                eventType = "API_HEALTH_CHANGED";
                severity = "WARNING";
                status = "degraded";
                summary = "Backend health check failed.";
                break;
            case BACKEND_REACHABLE:
                eventType = "BACKEND_REACHABLE";
                severity = "INFO";
                status = "reachable";
                summary = "Backend health check succeeded.";
                break;
            case BACKEND_UNREACHABLE:
                eventType = "BACKEND_UNREACHABLE";
                severity = "ERROR";
                status = "unreachable";
                summary = "Backend remained unreachable after repeated health failures.";
                break;
            case BACKEND_RECOVERED:
                eventType = "BACKEND_RECOVERED";
                severity = "RECOVERY";
                status = "recovered";
                summary = "Backend health was restored.";
                break;
            case RECOVERY_STARTED:
                eventType = "RECOVERY_STARTED";
                severity = "RECOVERY";
                status = "started";
                summary = "Bounded backend recovery was started.";
                break;
            case RECOVERY_SUCCESS:
                eventType = "RECOVERY_SUCCESS";
                severity = "RECOVERY";
                status = "accepted";
                summary = "Backend recovery command was accepted.";
                break;
            case RECOVERY_FAILED:
                eventType = "RECOVERY_FAILED";
                severity = "ERROR";
                status = "failed";
                summary = "Backend recovery command failed.";
                break;
            default:
                return;
        }

        JSONObject metadata = new JSONObject();
        try {
            metadata.put(
                    "state",
                    transition.getState().name()
            );
            metadata.put(
                    "consecutive_failures",
                    transition.getConsecutiveFailures()
            );
            metadata.put(
                    "recovery_attempts",
                    transition.getRecoveryAttempts()
            );
            if (httpStatus != null) {
                metadata.put("http_status", httpStatus);
            }
            if (transition.isAutomaticRecoveryExhausted()) {
                metadata.put("automatic_recovery_exhausted", true);
            }
        } catch (Exception ignored) {
            return;
        }

        record(
                eventType,
                severity,
                status,
                summary,
                metadata
        );
    }

    private void record(
            String eventType,
            String severity,
            String status,
            String summary,
            JSONObject metadata
    ) {
        journal.append(
                eventType,
                severity,
                "sentinel",
                status,
                summary,
                metadata
        );
    }

    private void syncUnsyncedEvents() {
        List<JSONObject> events = journal.readUnsynced(SYNC_BATCH_SIZE);
        if (events.isEmpty()) return;

        HttpURLConnection connection = null;
        try {
            JSONArray batch = new JSONArray();
            java.util.HashSet<String> submittedIds = new java.util.HashSet<>();
            for (JSONObject event : events) {
                String eventId = event.optString("event_id", "");
                if (eventId.isEmpty() || !submittedIds.add(eventId)) {
                    emitSyncFailure(null, "invalid_local_event_id");
                    return;
                }
                JSONObject upload = new JSONObject(event.toString());
                upload.remove("synced");
                batch.put(upload);
            }

            JSONObject request = new JSONObject();
            request.put("events", batch);

            connection = ApiConnection.open(this, SYNC_URL);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(2500);
            connection.setReadTimeout(5000);
            connection.setDoOutput(true);
            connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=UTF-8"
            );

            byte[] payload = request.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                emitSyncFailure(status, "http_error");
                return;
            }

            StringBuilder body = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            connection.getInputStream(),
                            StandardCharsets.UTF_8
                    ))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    body.append(line);
                }
            }

            JSONObject result = new JSONObject(body.toString());
            JSONArray acknowledged = result.optJSONArray("processed_event_ids");
            JSONArray rejectedArray = result.optJSONArray("rejected_event_ids");
            if (acknowledged == null || rejectedArray == null) {
                emitSyncFailure(null, "invalid_acknowledgement");
                return;
            }

            java.util.HashSet<String> acknowledgedIds = readAcknowledgementIds(acknowledged);
            java.util.HashSet<String> rejectedIds = readAcknowledgementIds(rejectedArray);
            int processed = result.optInt("processed", -1);
            int rejected = result.optInt("rejected", -1);

            if (acknowledgedIds == null
                    || rejectedIds == null
                    || processed != acknowledgedIds.size()
                    || rejected != rejectedIds.size()
                    || processed + rejected != submittedIds.size()
                    || !submittedIds.containsAll(acknowledgedIds)
                    || !submittedIds.containsAll(rejectedIds)) {
                emitSyncFailure(null, "invalid_acknowledgement");
                return;
            }

            java.util.HashSet<String> overlap = new java.util.HashSet<>(acknowledgedIds);
            overlap.retainAll(rejectedIds);
            if (!overlap.isEmpty()) {
                emitSyncFailure(null, "overlapping_acknowledgement");
                return;
            }

            // Only IDs explicitly confirmed as persisted by Core may be marked
            // synced. A failed local commit must leave them pending for retry.
            if (!acknowledgedIds.isEmpty()
                    && !journal.markSynced(acknowledgedIds)) {
                emitSyncFailure(null, "ack_persistence_failed");
                return;
            }

            if (rejected > 0) {
                JSONObject metadata = new JSONObject();
                metadata.put("rejected_count", rejected);

                JSONArray reasons = result.optJSONArray("rejection_reasons");
                if (reasons != null) {
                    for (int i = 0; i < reasons.length() && i < 8; i++) {
                        JSONObject item = reasons.optJSONObject(i);
                        if (item == null) continue;
                        String reason = item.optString("reason", "");
                        if (isSafeReasonCode(reason)) {
                            String key = "rejection_" + reason;
                            metadata.put(key, metadata.optInt(key, 0) + 1);
                        }
                    }
                }

                record(
                        "SENTINEL_SYNC_FAILED",
                        "WARNING",
                        "partial",
                        "Some Sentinel events were rejected during sync.",
                        metadata
                );
            }
        } catch (Exception ignored) {
            emitSyncFailure(null, "sync_exception");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private java.util.HashSet<String> readAcknowledgementIds(JSONArray values) {
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < values.length(); i++) {
            String id = values.optString(i, "");
            if (id.isEmpty() || !ids.add(id)) {
                return null;
            }
        }
        return ids;
    }

    private boolean isSafeReasonCode(String value) {
        if (value == null || value.isEmpty() || value.length() > 48) return false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!((ch >= 'a' && ch <= 'z')
                    || (ch >= '0' && ch <= '9')
                    || ch == '_')) {
                return false;
            }
        }
        return true;
    }

    private void emitSyncFailure(Integer httpStatus, String reasonCode) {
        JSONObject metadata = new JSONObject();
        try {
            if (httpStatus != null) {
                metadata.put("http_status", httpStatus);
            }
            if (isSafeReasonCode(reasonCode)) {
                metadata.put("reason_code", reasonCode);
            }
        } catch (Exception ignored) {
            return;
        }
        record(
                "SENTINEL_SYNC_FAILED",
                "WARNING",
                "failed",
                "Sentinel could not sync its local journal.",
                metadata
        );
    }

    private boolean startForegroundSafely() {
        Notification notification = buildNotification();
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                );
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, MobileShellActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                4012,
                launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String state = stateMachine == null
                ? "STARTING"
                : stateMachine.getState().name();

        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Amiki Sentinel")
                .setContentText("Monitoring backend • " + state)
                .setOngoing(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(pendingIntent)
                .build();
    }

    private void updateNotification() {
        if (!started || stateMachine == null) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE
                );
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Amiki Sentinel",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(
                "Shows that Amiki Sentinel is monitoring backend availability."
        );
        channel.setShowBadge(false);

        NotificationManager manager =
                (NotificationManager) getSystemService(
                        Context.NOTIFICATION_SERVICE
                );
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }

    private static final class HealthCheck {
        final boolean healthy;
        final Integer httpStatus;

        HealthCheck(boolean healthy, Integer httpStatus) {
            this.healthy = healthy;
            this.httpStatus = httpStatus;
        }
    }
}
