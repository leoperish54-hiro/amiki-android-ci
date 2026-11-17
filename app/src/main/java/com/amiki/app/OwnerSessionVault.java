package com.amiki.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores a short-lived UOSA session token encrypted with a dedicated Keystore AES-GCM key. */
final class OwnerSessionVault {
    private static final String PREFS = "amiki_uosa_session";
    private static final String CIPHERTEXT = "token_ciphertext";
    private static final String IV = "token_iv";
    private static final String EXPIRES_AT = "expires_at_epoch_seconds";
    private static final String KEY_ALIAS = "com.amiki.app.uosa-session-token";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;

    private OwnerSessionVault() {}

    static boolean hasToken(Context context) {
        try {
            return readToken(context) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    static void saveToken(Context context, String token, long expiresAtEpochSeconds) throws Exception {
        if (token == null || token.trim().length() < 32 || token.length() > 256) {
            throw new IllegalArgumentException("Invalid UOSA session token.");
        }
        long now = System.currentTimeMillis() / 1000L;
        if (expiresAtEpochSeconds <= now || expiresAtEpochSeconds > now + 1800L) {
            throw new IllegalArgumentException("Invalid UOSA session expiry.");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new IllegalStateException("Android Keystore AES-GCM is unavailable.");
        }
        byte[] plaintext = token.trim().getBytes(StandardCharsets.UTF_8);
        byte[] iv = null;
        byte[] encrypted = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            iv = cipher.getIV();
            encrypted = cipher.doFinal(plaintext);
            preferences(context).edit()
                    .putString(IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                    .putString(CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                    .putLong(EXPIRES_AT, expiresAtEpochSeconds)
                    .apply();
        } finally {
            Arrays.fill(plaintext, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
        }
    }

    static String readToken(Context context) throws Exception {
        SharedPreferences prefs = preferences(context);
        String encodedIv = prefs.getString(IV, null);
        String encodedCiphertext = prefs.getString(CIPHERTEXT, null);
        long expiresAt = prefs.getLong(EXPIRES_AT, 0L);
        if (encodedIv == null || encodedCiphertext == null || expiresAt <= 0L) return null;
        if (expiresAt <= System.currentTimeMillis() / 1000L) {
            clear(context);
            return null;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new IllegalStateException("Android Keystore AES-GCM is unavailable.");
        }
        byte[] iv = null;
        byte[] encrypted = null;
        byte[] plaintext = null;
        try {
            iv = Base64.decode(encodedIv, Base64.NO_WRAP);
            encrypted = Base64.decode(encodedCiphertext, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            clear(context);
            throw failure;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
        }
    }

    static long expiresAt(Context context) {
        return preferences(context).getLong(EXPIRES_AT, 0L);
    }

    static void clear(Context context) {
        preferences(context).edit()
                .remove(CIPHERTEXT)
                .remove(IV)
                .remove(EXPIRES_AT)
                .apply();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // Clearing ciphertext disables use even if Keystore cleanup fails.
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            java.security.Key key = keyStore.getKey(KEY_ALIAS, null);
            if (key instanceof SecretKey) return (SecretKey) key;
            keyStore.deleteEntry(KEY_ALIAS);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
