package com.amiki.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

/** Creates and persists one retry identity for a single Cloud goal submission. */
final class CloudGoalIdempotencyKey {
    interface Store {
        String fingerprint();
        String key();
        boolean save(String fingerprint, String key);
        boolean clear();
    }

    private CloudGoalIdempotencyKey() {}

    static String fingerprint(String title, String objective) {
        if (title == null || objective == null) {
            throw new IllegalArgumentException("Goal title and objective are required.");
        }
        String normalizedTitle = title.trim();
        String normalizedObjective = objective.trim();
        String payload = normalizedTitle.length() + ":" + normalizedTitle
                + ":" + normalizedObjective.length() + ":" + normalizedObjective;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                int unsigned = value & 0xff;
                if (unsigned < 16) hex.append('0');
                hex.append(Integer.toHexString(unsigned));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException unavailable) {
            throw new IllegalStateException("SHA-256 is unavailable.", unavailable);
        }
    }

    static String getOrCreate(Store store, String fingerprint) {
        if (store == null || !isFingerprint(fingerprint)) {
            throw new IllegalArgumentException("A valid Cloud goal fingerprint is required.");
        }
        String existingKey = store.key();
        if (fingerprint.equals(store.fingerprint()) && isCanonicalUuid(existingKey)) {
            return existingKey;
        }

        String newKey = UUID.randomUUID().toString();
        if (!store.save(fingerprint, newKey)) {
            throw new IllegalStateException("Unable to persist the Cloud goal retry key.");
        }
        return newKey;
    }

    static boolean clearIfMatches(Store store, String fingerprint, String key) {
        if (store == null || !isFingerprint(fingerprint) || !isCanonicalUuid(key)) {
            return false;
        }
        if (!fingerprint.equals(store.fingerprint()) || !key.equals(store.key())) {
            return false;
        }
        return store.clear();
    }

    private static boolean isFingerprint(String value) {
        if (value == null || value.length() != 64) return false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (!((character >= '0' && character <= '9')
                    || (character >= 'a' && character <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null) return false;
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException invalidUuid) {
            return false;
        }
    }
}
