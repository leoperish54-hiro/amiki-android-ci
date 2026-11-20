package com.amiki.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.ByteBuffer;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM/Android-Keystore vault for provider credentials such as Picovoice AccessKey. */
public final class AmikiSecretStore {
    private static final String PREFS = "amiki_encrypted_provider_secrets";
    private static final String KEY_ALIAS = "com.amiki.app.provider-secrets.v1";
    private static final int GCM_TAG_BITS = 128;

    private AmikiSecretStore() { }

    public static void put(Context context, String name, String value) throws Exception {
        if (context == null || name == null || name.trim().isEmpty() || value == null) {
            throw new IllegalArgumentException("A secret name and value are required.");
        }
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] encrypted = cipher.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        ByteBuffer buffer = ByteBuffer.allocate(4 + iv.length + encrypted.length);
        buffer.putInt(iv.length);
        buffer.put(iv);
        buffer.put(encrypted);
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(name, Base64.encodeToString(buffer.array(), Base64.NO_WRAP)).apply();
    }

    public static String get(Context context, String name) throws Exception {
        if (context == null || name == null || name.trim().isEmpty()) return null;
        String encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(name, null);
        if (encoded == null || encoded.isEmpty()) return null;
        byte[] payload = Base64.decode(encoded, Base64.NO_WRAP);
        ByteBuffer buffer = ByteBuffer.wrap(payload);
        if (buffer.remaining() < 4) throw new IllegalStateException("Encrypted secret is truncated.");
        int ivLength = buffer.getInt();
        if (ivLength < 12 || ivLength > 16 || buffer.remaining() <= ivLength) {
            throw new IllegalStateException("Encrypted secret has an invalid GCM nonce.");
        }
        byte[] iv = new byte[ivLength];
        buffer.get(iv);
        byte[] encrypted = new byte[buffer.remaining()];
        buffer.get(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8);
    }

    public static void remove(Context context, String name) {
        if (context == null || name == null) return;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(name).apply();
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        java.security.Key existing = store.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) return (SecretKey) existing;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
