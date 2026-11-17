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

/**
 * Stores the narrow ALE owner-review bearer token encrypted with an Android
 * Keystore AES-GCM key. The plaintext token is never persisted in preferences.
 */
final class OwnerTokenVault {
    private static final String PREFS = "amiki_owner_review";
    private static final String CIPHERTEXT = "token_ciphertext";
    private static final String IV = "token_iv";
    private static final String KEY_ALIAS = "com.amiki.app.owner-review-token";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;

    private OwnerTokenVault() {}

    static boolean hasToken(Context context) {
        return preferences(context).contains(CIPHERTEXT)
                && preferences(context).contains(IV);
    }

    static void saveToken(Context context, String token) throws Exception {
        if (token == null || token.trim().length() < 32 || token.length() > 4096) {
            throw new IllegalArgumentException("Owner token must contain 32–4096 characters.");
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            throw new IllegalStateException("Android Keystore AES-GCM requires Android 6.0 or newer.");
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
        if (encodedIv == null || encodedCiphertext == null) return null;
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
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
        }
    }

    static void clear(Context context) {
        preferences(context).edit().remove(CIPHERTEXT).remove(IV).apply();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS);
        } catch (Exception ignored) {
            // Removing the stored ciphertext is sufficient to disable token use.
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

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
