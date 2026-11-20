package com.amiki.app;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;

/** Serializes silent general-grant renewal using a distinct non-exportable Keystore key. */
final class OwnerSessionProvider {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String CONTINUITY_ALIAS = "amiki.uosa.continuity.v1";
    private static final Object LOCK = new Object();

    private OwnerSessionProvider() {}

    static String getGeneralToken(Context context) throws Exception {
        synchronized (LOCK) {
            Context app = context.getApplicationContext();
            String current = OwnerSessionVault.readGeneralToken(app);
            if (current != null && !current.isEmpty()) return current;

            String grantId = OwnerSessionVault.generalGrantId(app);
            if (grantId == null || grantId.trim().isEmpty()) return null;
            String deviceId = app.getSharedPreferences("amiki_uosa_device", Context.MODE_PRIVATE)
                    .getString("device_id", null);
            if (deviceId == null || deviceId.trim().isEmpty()) {
                invalidate(app);
                throw new IOException("The enrolled device identity is missing; general access was cleared.");
            }

            try {
                KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
                keyStore.load(null);
                if (!keyStore.containsAlias(CONTINUITY_ALIAS)) {
                    invalidate(app);
                    throw new IOException("The device continuity key is unavailable. Re-authorize general access in Owner Security.");
                }
                JSONObject challengeRequest = new JSONObject();
                JSONObject challenge = postJson(
                        ApiEndpoints.UOSA_GENERAL_GRANT_REFRESH_CHALLENGES_PREFIX + grantId,
                        challengeRequest);
                String signature = signContinuityChallenge(keyStore,
                        challenge.getString("message"));
                JSONObject completion = new JSONObject();
                completion.put("challenge_id", challenge.getString("challenge_id"));
                completion.put("signature", signature);
                JSONObject refreshed = postJson(ApiEndpoints.UOSA_GENERAL_GRANT_REFRESH, completion);
                if (!grantId.equals(refreshed.optString("grant_id"))
                        || !"general".equals(refreshed.optString("session_class"))
                        || !refreshed.optBoolean("grant_persistent", false)) {
                    throw new IOException("The backend returned an unexpected general-session response.");
                }
                OwnerSessionVault.saveGeneralToken(app, refreshed.getString("access_token"),
                        refreshed.getLong("expires_at"), grantId);
                return refreshed.getString("access_token");
            } catch (Exception failure) {
                if (isRevokedResponse(failure)) invalidate(app);
                throw failure;
            }
        }
    }

    static String getPrivilegedToken(Context context) throws Exception {
        return OwnerSessionVault.readPrivilegedToken(context.getApplicationContext());
    }

    private static String signContinuityChallenge(KeyStore keyStore, String message)
            throws Exception {
        PrivateKey key = (PrivateKey) keyStore.getKey(CONTINUITY_ALIAS, null);
        if (key == null) throw new IOException("The continuity signing key is missing.");
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key);
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(signer.sign(), Base64.NO_WRAP);
    }

    private static JSONObject postJson(String endpoint, JSONObject body) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(12000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readBody(stream);
            if (status < 200 || status >= 300) {
                String detail = "request rejected";
                try { detail = new JSONObject(response).optString("detail", detail); }
                catch (Exception ignored) { }
                throw new IOException("Backend HTTP " + status + ": " + detail);
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readBody(InputStream stream) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
                if (body.length() > 65536) throw new IOException("Backend response exceeded safe limit.");
            }
        }
        return body.toString();
    }

    private static boolean isRevokedResponse(Exception failure) {
        String message = failure.getMessage();
        return message != null && (message.contains("Backend HTTP 401")
                || message.contains("Backend HTTP 403"));
    }

    private static void invalidate(Context context) {
        OwnerSessionVault.clearGeneral(context);
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(CONTINUITY_ALIAS)) keyStore.deleteEntry(CONTINUITY_ALIAS);
        } catch (Exception ignored) {
            // Removing the grant metadata prevents further renewal attempts.
        }
    }
}
