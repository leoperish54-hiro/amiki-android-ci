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

/** Separate encrypted stores for renewable general access and 15-minute privileged sessions. */
final class OwnerSessionVault {
    private static final String GENERAL_PREFS = "amiki_uosa_general_session";
    private static final String PRIVILEGED_PREFS = "amiki_uosa_privileged_session";
    private static final String CIPHERTEXT = "token_ciphertext";
    private static final String IV = "token_iv";
    private static final String EXPIRES_AT = "expires_at_epoch_seconds";
    private static final String GRANT_ID = "persistent_grant_id";
    private static final String GENERAL_KEY_ALIAS = "com.amiki.app.uosa-general-session-token";
    private static final String PRIVILEGED_KEY_ALIAS = "com.amiki.app.uosa-privileged-session-token";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final int GCM_TAG_BITS = 128;

    private OwnerSessionVault() {}

    static boolean hasToken(Context context) {
        return hasGeneralToken(context);
    }

    static boolean hasGeneralToken(Context context) {
        try { return readGeneralToken(context) != null; }
        catch (Exception ignored) { return false; }
    }

    static boolean hasPrivilegedToken(Context context) {
        try { return readPrivilegedToken(context) != null; }
        catch (Exception ignored) { return false; }
    }

    static void saveGeneralToken(
            Context context, String token, long expiresAtEpochSeconds, String grantId
    ) throws Exception {
        if (grantId == null || grantId.trim().length() < 32 || grantId.length() > 128) {
            throw new IllegalArgumentException("Invalid UOSA persistent grant identifier.");
        }
        saveProfile(context, true, token, expiresAtEpochSeconds);
        generalPreferences(context).edit().putString(GRANT_ID, grantId).apply();
    }

    static void savePrivilegedToken(Context context, String token, long expiresAtEpochSeconds)
            throws Exception {
        saveProfile(context, false, token, expiresAtEpochSeconds);
    }

    static String readGeneralToken(Context context) throws Exception {
        return readProfile(context, true);
    }

    static String readPrivilegedToken(Context context) throws Exception {
        return readProfile(context, false);
    }

    /** Compatibility: general capability requests use the persistent general profile. */
    static void saveToken(Context context, String token, long expiresAtEpochSeconds) throws Exception {
        saveProfile(context, true, token, expiresAtEpochSeconds);
    }

    static String readToken(Context context) throws Exception {
        return readGeneralToken(context);
    }

    static String generalGrantId(Context context) {
        return generalPreferences(context).getString(GRANT_ID, null);
    }

    static long expiresAt(Context context) {
        return generalPreferences(context).getLong(EXPIRES_AT, 0L);
    }

    static long privilegedExpiresAt(Context context) {
        return privilegedPreferences(context).getLong(EXPIRES_AT, 0L);
    }

    static void clearExpiredGeneralToken(Context context) {
        clearAccessToken(context, true);
    }

    static void clearGeneral(Context context) {
        clearAccessToken(context, true);
        generalPreferences(context).edit().remove(GRANT_ID).apply();
    }

    static void clearPrivileged(Context context) {
        clearAccessToken(context, false);
    }

    static void clear(Context context) {
        clearGeneral(context);
        clearPrivileged(context);
    }

    private static void saveProfile(
            Context context, boolean general, String token, long expiresAtEpochSeconds
    ) throws Exception {
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
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(general));
            iv = cipher.getIV();
            encrypted = cipher.doFinal(plaintext);
            SharedPreferences prefs = general ? generalPreferences(context) : privilegedPreferences(context);
            prefs.edit()
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

    private static String readProfile(Context context, boolean general) throws Exception {
        SharedPreferences prefs = general ? generalPreferences(context) : privilegedPreferences(context);
        String encodedIv = prefs.getString(IV, null);
        String encodedCiphertext = prefs.getString(CIPHERTEXT, null);
        long expiresAt = prefs.getLong(EXPIRES_AT, 0L);
        if (encodedIv == null || encodedCiphertext == null || expiresAt <= 0L) return null;
        if (expiresAt <= System.currentTimeMillis() / 1000L) {
            clearAccessToken(context, general);
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
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(general),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            plaintext = cipher.doFinal(encrypted);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            clearAccessToken(context, general);
            throw failure;
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
            if (encrypted != null) Arrays.fill(encrypted, (byte) 0);
            if (iv != null) Arrays.fill(iv, (byte) 0);
        }
    }

    private static void clearAccessToken(Context context, boolean general) {
        (general ? generalPreferences(context) : privilegedPreferences(context)).edit()
                .remove(CIPHERTEXT).remove(IV).remove(EXPIRES_AT).apply();
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            String alias = general ? GENERAL_KEY_ALIAS : PRIVILEGED_KEY_ALIAS;
            if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias);
        } catch (Exception ignored) {
            // Ciphertext removal disables use even if Keystore cleanup fails.
        }
    }

    private static SharedPreferences generalPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(GENERAL_PREFS, Context.MODE_PRIVATE);
    }

    private static SharedPreferences privilegedPreferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(PRIVILEGED_PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey(boolean general) throws Exception {
        String alias = general ? GENERAL_KEY_ALIAS : PRIVILEGED_KEY_ALIAS;
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(alias)) {
            java.security.Key key = keyStore.getKey(alias, null);
            if (key instanceof SecretKey) return (SecretKey) key;
            keyStore.deleteEntry(alias);
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
