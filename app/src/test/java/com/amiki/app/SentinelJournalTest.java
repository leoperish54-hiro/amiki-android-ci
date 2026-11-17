package com.amiki.app;

import org.json.JSONObject;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class SentinelJournalTest {
    private static final class MemoryStore implements SentinelJournal.Store {
        private final Map<String, String> values = new HashMap<>();
        boolean failWrites;

        @Override
        public String get(String key, String fallback) {
            return values.containsKey(key) ? values.get(key) : fallback;
        }

        @Override
        public boolean put(String key, String value) {
            if (failWrites) return false;
            values.put(key, value);
            return true;
        }
    }

    @Test
    public void appendsBoundedEventsWithIncreasingSequenceNumbers() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal journal = new SentinelJournal(store, 3);

        assertTrue(journal.append(
                "BACKEND_UNREACHABLE",
                "ERROR",
                "sentinel",
                "unreachable",
                "Backend unavailable.",
                new JSONObject().put("http_status", 503)
        ));
        assertTrue(journal.append(
                "RECOVERY_STARTED",
                "RECOVERY",
                "sentinel",
                "started",
                "Recovery started.",
                null
        ));

        List<org.json.JSONObject> events = journal.readUnsynced(10);

        assertEquals(2, events.size());
        assertEquals(1, events.get(0).getInt("sequence_number"));
        assertEquals(2, events.get(1).getInt("sequence_number"));
        assertEquals("android_sentinel", events.get(0).getString("source"));
    }

    @Test
    public void redactsSensitiveMetadataBeforePersistence() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal journal = new SentinelJournal(store, 3);

        assertTrue(journal.append(
                "API_HEALTH_CHANGED",
                "WARNING",
                "sentinel",
                "degraded",
                "Health check failed.",
                new JSONObject()
                        .put("authorization", "Bearer supersecret")
                        .put("safe_status", 503)
        ));

        JSONObject event = journal.readUnsynced(1).get(0);
        assertEquals("[REDACTED]", event.getJSONObject("metadata").getString("authorization"));
        assertEquals(503, event.getJSONObject("metadata").getInt("safe_status"));
    }

    @Test
    public void syncedRecordsAreRetainedButNotReturnedForUpload() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal journal = new SentinelJournal(store, 3);

        assertTrue(journal.append(
                "BACKEND_REACHABLE",
                "INFO",
                "sentinel",
                "reachable",
                "Backend is reachable.",
                null
        ));
        String id = journal.readUnsynced(1).get(0).getString("event_id");
        assertTrue(journal.markSynced(java.util.Collections.singleton(id)));

        assertEquals(1, journal.count());
        assertEquals(0, journal.unsyncedCount());
        assertTrue(journal.readUnsynced(10).isEmpty());
    }

    @Test
    public void oldestSyncedRecordsAreEvictedBeforeUnsyncedRecords() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal journal = new SentinelJournal(store, 2);

        assertTrue(journal.append(
                "BACKEND_REACHABLE",
                "INFO",
                "sentinel",
                "reachable",
                "one",
                null
        ));
        String first = journal.readUnsynced(1).get(0).getString("event_id");
        assertTrue(journal.markSynced(java.util.Collections.singleton(first)));

        assertTrue(journal.append(
                "BACKEND_UNREACHABLE",
                "ERROR",
                "sentinel",
                "unreachable",
                "two",
                null
        ));
        assertTrue(journal.append(
                "RECOVERY_STARTED",
                "RECOVERY",
                "sentinel",
                "started",
                "three",
                null
        ));

        assertEquals(2, journal.count());
        assertEquals(2, journal.unsyncedCount());
    }

    @Test
    public void invalidEventTypesAreRejected() {
        SentinelJournal journal = new SentinelJournal(new MemoryStore(), 2);

        assertFalse(journal.append(
                "MODEL_FAILURE",
                "ERROR",
                "sentinel",
                "failed",
                "not a Sentinel event",
                null
        ));

    }

    @Test
    public void sequenceNumbersRemainMonotonicAcrossNewJournalInstances() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal first = new SentinelJournal(store, 5);
        assertTrue(first.append(
                "BACKEND_REACHABLE",
                "INFO",
                "sentinel",
                "reachable",
                "first",
                null
        ));

        SentinelJournal second = new SentinelJournal(store, 5);
        assertTrue(second.append(
                "API_HEALTH_CHANGED",
                "WARNING",
                "sentinel",
                "degraded",
                "second",
                null
        ));

        List<org.json.JSONObject> events = second.readUnsynced(10);
        assertEquals(1, events.get(0).getLong("sequence_number"));
        assertEquals(2, events.get(1).getLong("sequence_number"));
    }

    @Test
    public void recentEventsAreReturnedNewestWindowWithoutExposingMetadataPolicy() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal journal = new SentinelJournal(store, 5);

        journal.append("BACKEND_REACHABLE", "INFO", "sentinel", "reachable", "one", null);
        journal.append("BACKEND_UNREACHABLE", "ERROR", "sentinel", "unreachable", "two", null);
        journal.append("RECOVERY_STARTED", "RECOVERY", "sentinel", "started", "three", null);

        List<org.json.JSONObject> events = journal.readRecent(2);

        assertEquals(2, events.size());
        assertEquals("BACKEND_UNREACHABLE", events.get(0).getString("event_type"));
        assertEquals("RECOVERY_STARTED", events.get(1).getString("event_type"));
    }

    @Test
    public void backendFailureEventsCarryStableDiagnosticSignature() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal journal = new SentinelJournal(store, 5);

        assertTrue(journal.append(
                "BACKEND_UNREACHABLE",
                "ERROR",
                "sentinel",
                "unreachable",
                "backend down",
                null
        ));

        org.json.JSONObject event = journal.readUnsynced(1).get(0);
        assertEquals(
                SentinelJournal.BACKEND_FAILURE_SIGNATURE,
                event.getString("failure_signature")
        );
    }


    @Test
    public void failedAcknowledgementCommitLeavesEventPendingAndPreserved() throws Exception {
        MemoryStore store = new MemoryStore();
        SentinelJournal journal = new SentinelJournal(store, 3);

        assertTrue(journal.append(
                "BACKEND_REACHABLE",
                "INFO",
                "sentinel",
                "reachable",
                "Backend is reachable.",
                null
        ));
        String eventId = journal.readUnsynced(1).get(0).getString("event_id");

        store.failWrites = true;
        assertFalse(journal.markSynced(java.util.Collections.singleton(eventId)));

        SentinelJournal reopened = new SentinelJournal(store, 3);
        assertEquals(1, reopened.unsyncedCount());
        assertEquals(eventId, reopened.readUnsynced(1).get(0).getString("event_id"));
    }
}
