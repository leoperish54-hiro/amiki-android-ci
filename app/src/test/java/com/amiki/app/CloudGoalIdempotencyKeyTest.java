package com.amiki.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CloudGoalIdempotencyKeyTest {
    @Test
    public void fingerprintIsStableForTrimmedGoalIntent() {
        String first = CloudGoalIdempotencyKey.fingerprint("  Prepare a project  ", "  Deliver v1.  ");
        String second = CloudGoalIdempotencyKey.fingerprint("Prepare a project", "Deliver v1.");
        assertEquals(first, second);
        assertEquals(64, first.length());
    }

    @Test
    public void retryReusesKeyButChangedGoalIntentGetsANewKey() {
        MemoryStore store = new MemoryStore();
        String fingerprint = CloudGoalIdempotencyKey.fingerprint("Prepare a project", "Deliver v1.");
        String first = CloudGoalIdempotencyKey.getOrCreate(store, fingerprint);
        String retry = CloudGoalIdempotencyKey.getOrCreate(store, fingerprint);

        assertEquals(first, retry);

        String changedFingerprint = CloudGoalIdempotencyKey.fingerprint(
                "Prepare a project", "Deliver v2."
        );
        String changed = CloudGoalIdempotencyKey.getOrCreate(store, changedFingerprint);
        assertNotEquals(first, changed);
        assertEquals(changedFingerprint, store.fingerprint());
    }

    @Test
    public void completionClearsOnlyTheMatchingPendingSubmission() {
        MemoryStore store = new MemoryStore();
        String fingerprint = CloudGoalIdempotencyKey.fingerprint("Prepare a project", "Deliver v1.");
        String key = CloudGoalIdempotencyKey.getOrCreate(store, fingerprint);
        String otherFingerprint = CloudGoalIdempotencyKey.fingerprint(
                "Prepare a project", "Deliver v2."
        );

        assertFalse(CloudGoalIdempotencyKey.clearIfMatches(store, otherFingerprint, key));
        assertEquals(key, store.key());
        assertTrue(CloudGoalIdempotencyKey.clearIfMatches(store, fingerprint, key));
        assertNull(store.key());
        assertNull(store.fingerprint());
    }

    @Test(expected = IllegalStateException.class)
    public void failedPersistenceDoesNotSendAnUntrackedRequest() {
        CloudGoalIdempotencyKey.getOrCreate(
                new MemoryStore() {
                    @Override
                    public boolean save(String fingerprint, String key) {
                        return false;
                    }
                },
                CloudGoalIdempotencyKey.fingerprint("Prepare a project", "Deliver v1.")
        );
    }

    private static class MemoryStore implements CloudGoalIdempotencyKey.Store {
        private String fingerprint;
        private String key;

        @Override
        public String fingerprint() {
            return fingerprint;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public boolean save(String fingerprint, String key) {
            this.fingerprint = fingerprint;
            this.key = key;
            return true;
        }

        @Override
        public boolean clear() {
            fingerprint = null;
            key = null;
            return true;
        }
    }
}
