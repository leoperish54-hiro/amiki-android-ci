package com.amiki.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.UUID;

/** Durable, bounded local journal used by Sentinel while the backend is unavailable. */
public final class SentinelJournal {
    public static final int SCHEMA_VERSION = 1;
    public static final int DEFAULT_MAX_RECORDS = 200;
    public static final long RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    public static final String BACKEND_FAILURE_SIGNATURE = "sentinel_backend_unavailable_v1";

    private static final String PREFS = "amiki_sentinel_journal";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_NEXT_SEQUENCE = "next_sequence";

    private static final Set<String> ALLOWED_EVENTS = new HashSet<>(Arrays.asList(
            "SENTINEL_STARTED",
            "SENTINEL_STOPPED",
            "SENTINEL_SYNC_SUCCESS",
            "SENTINEL_SYNC_FAILED",
            "BACKEND_REACHABLE",
            "BACKEND_UNREACHABLE",
            "BACKEND_RECOVERED",
            "RECOVERY_STARTED",
            "RECOVERY_SUCCESS",
            "RECOVERY_FAILED",
            "API_HEALTH_CHANGED"
    ));

    interface Store {
        String get(String key, String fallback);
        boolean put(String key, String value);
    }

    static final class SharedPreferencesStore implements Store {
        private final SharedPreferences preferences;

        SharedPreferencesStore(SharedPreferences preferences) {
            this.preferences = preferences;
        }

        @Override
        public String get(String key, String fallback) {
            return preferences.getString(key, fallback);
        }

        @Override
        public boolean put(String key, String value) {
            return preferences.edit().putString(key, value).commit();
        }
    }

    private final Store store;
    private final int maxRecords;
    private final Object lock = new Object();

    public SentinelJournal(Context context) {
        this(
                new SharedPreferencesStore(
                        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                ),
                DEFAULT_MAX_RECORDS
        );
    }

    SentinelJournal(Store store, int maxRecords) {
        if (store == null || maxRecords < 1) {
            throw new IllegalArgumentException("Sentinel journal configuration is invalid.");
        }
        this.store = store;
        this.maxRecords = maxRecords;
    }

    public boolean append(
            String eventType,
            String severity,
            String component,
            String status,
            String summary,
            JSONObject metadata
    ) {
        if (!ALLOWED_EVENTS.contains(eventType)
                || !safeText(severity, 32)
                || !safeText(component, 80)
                || !safeText(status, 32)
                || !safeText(summary, 500)) {
            return false;
        }

        synchronized (lock) {
            JSONArray events = readArray();
            pruneArray(events, System.currentTimeMillis());

            if (events.length() >= maxRecords) {
                int removable = findOldestSyncedIndex(events);
                if (removable < 0) {
                    return false;
                }
                events.remove(removable);
            }

            try {
                long sequence = nextSequence();
                JSONObject event = new JSONObject();
                event.put("schema_version", SCHEMA_VERSION);
                event.put("event_id", "sentinel_" + UUID.randomUUID().toString().replace("-", ""));
                event.put("event_type", eventType);
                event.put("severity", severity);
                event.put("source", "android_sentinel");
                event.put("component", component);
                event.put("observed_at", nowUtc());
                event.put("correlation_id", JSONObject.NULL);
                event.put("parent_event_id", JSONObject.NULL);
                event.put("provenance", "observed");
                event.put("status", status);
                event.put("summary", summary);
                event.put("metadata", sanitizeMetadata(metadata));
                if ("BACKEND_UNREACHABLE".equals(eventType) || "RECOVERY_FAILED".equals(eventType)
                        || "BACKEND_RECOVERED".equals(eventType)) {
                    event.put("failure_signature", BACKEND_FAILURE_SIGNATURE);
                }
                event.put("sequence_number", sequence);
                event.put("synced", false);

                events.put(event);
                return store.put(KEY_EVENTS, events.toString());
            } catch (Exception ignored) {
                return false;
            }
        }
    }

    public List<JSONObject> readRecent(int limit) {
        if (limit < 1) {
            return new ArrayList<>();
        }
        synchronized (lock) {
            JSONArray events = readArray();
            List<JSONObject> result = new ArrayList<>();
            int start = Math.max(0, events.length() - limit);
            for (int i = start; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null) {
                    result.add(copy(event));
                }
            }
            return result;
        }
    }

    public List<JSONObject> readUnsynced(int limit) {
        if (limit < 1) {
            return new ArrayList<>();
        }
        synchronized (lock) {
            JSONArray events = readArray();
            List<JSONObject> result = new ArrayList<>();
            for (int i = 0; i < events.length() && result.size() < limit; i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null && !event.optBoolean("synced", false)) {
                    result.add(copy(event));
                }
            }
            return result;
        }
    }

    public boolean markSynced(Set<String> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return true;
        }
        synchronized (lock) {
            JSONArray events = readArray();
            boolean changed = false;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event == null) continue;
                String id = event.optString("event_id", "");
                if (eventIds.contains(id) && !event.optBoolean("synced", false)) {
                    try {
                        event.put("synced", true);
                        changed = true;
                    } catch (Exception ignored) {
                        // Keep the event pending for a later sync attempt.
                    }
                }
            }
            return !changed || store.put(KEY_EVENTS, events.toString());
        }
    }

    public int count() {
        synchronized (lock) {
            return readArray().length();
        }
    }

    public int unsyncedCount() {
        synchronized (lock) {
            JSONArray events = readArray();
            int count = 0;
            for (int i = 0; i < events.length(); i++) {
                JSONObject event = events.optJSONObject(i);
                if (event != null && !event.optBoolean("synced", false)) count++;
            }
            return count;
        }
    }

    public int prune() {
        synchronized (lock) {
            JSONArray events = readArray();
            int before = events.length();
            pruneArray(events, System.currentTimeMillis());
            if (events.length() != before) {
                store.put(KEY_EVENTS, events.toString());
            }
            return before - events.length();
        }
    }

    private JSONArray readArray() {
        String raw = store.get(KEY_EVENTS, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private void pruneArray(JSONArray events, long nowMillis) {
        for (int i = events.length() - 1; i >= 0; i--) {
            JSONObject event = events.optJSONObject(i);
            if (event == null) {
                events.remove(i);
                continue;
            }
            if (isExpired(event, nowMillis)) {
                events.remove(i);
            }
        }
        while (events.length() > maxRecords) {
            int removable = findOldestSyncedIndex(events);
            if (removable < 0) {
                break;
            }
            events.remove(removable);
        }
    }

    private int findOldestSyncedIndex(JSONArray events) {
        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.optJSONObject(i);
            if (event != null && event.optBoolean("synced", false)) {
                return i;
            }
        }
        return -1;
    }

    private long nextSequence() {
        long next = 1L;
        try {
            next = Math.max(1L, Long.parseLong(
                    store.get(KEY_NEXT_SEQUENCE, "1")
            ));
        } catch (NumberFormatException ignored) {
            next = 1L;
        }
        store.put(KEY_NEXT_SEQUENCE, String.valueOf(next + 1L));
        return next;
    }

    private boolean isExpired(JSONObject event, long nowMillis) {
        String timestamp = event.optString("observed_at", "");
        try {
            Date date = parser().parse(timestamp);
            return date != null && nowMillis - date.getTime() > RETENTION_MILLIS;
        } catch (ParseException ignored) {
            return true;
        }
    }

    private static JSONObject copy(JSONObject source) {
        try {
            return new JSONObject(source.toString());
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static boolean safeText(String value, int max) {
        return value != null && !value.trim().isEmpty() && value.length() <= max;
    }

    private static JSONObject sanitizeMetadata(JSONObject input) {
        JSONObject safe = new JSONObject();
        if (input == null) return safe;

        Set<String> sensitive = new HashSet<>(Arrays.asList(
                "api_key", "authorization", "cookie", "password", "secret",
                "token", "access_token", "refresh_token", "prompt",
                "conversation", "attachment", "payload", "raw_content"
        ));

        JSONArray names = input.names();
        if (names == null) return safe;

        for (int i = 0; i < Math.min(names.length(), 16); i++) {
            String key = names.optString(i, "").trim();
            if (key.isEmpty() || key.length() > 64) continue;

            boolean redacted = false;
            String normalized = key.toLowerCase(Locale.ROOT)
                    .replace("-", "_")
                    .replace(" ", "_");
            for (String part : sensitive) {
                if (normalized.contains(part)) {
                    redacted = true;
                    break;
                }
            }

            try {
                safe.put(key, redacted ? "[REDACTED]" : boundedJsonValue(input.opt(key)));
            } catch (Exception ignored) {
                // Ignore unsafe optional metadata.
            }
        }
        return safe;
    }

    private static Object boundedJsonValue(Object value) {
        if (value == null || value == JSONObject.NULL
                || value instanceof Boolean || value instanceof Number) {
            return value == null ? JSONObject.NULL : value;
        }
        if (value instanceof String) {
            String text = ((String) value).trim();
            return text.length() > 300 ? text.substring(0, 300) : text;
        }
        return "[REDACTED]";
    }

    private static SimpleDateFormat parser() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format;
    }

    private static String nowUtc() {
        return parser().format(new Date());
    }
}
