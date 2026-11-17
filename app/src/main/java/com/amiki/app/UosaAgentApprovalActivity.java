package com.amiki.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.concurrent.Executor;

/** Fresh, transaction-bound L2 owner approval for one direct agent invocation. */
public final class UosaAgentApprovalActivity extends FragmentActivity {
    static final String EXTRA_REQUEST_ID = "com.amiki.app.extra.UOSA_AGENT_REQUEST_ID";
    static final String EXTRA_REQUEST_JSON = "com.amiki.app.extra.UOSA_AGENT_REQUEST_JSON";
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private String requestId;
    private JSONObject request;
    private String transactionHash;
    private TextView status;
    private Executor executor;
    private boolean completed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        executor = ContextCompat.getMainExecutor(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        requestId = getIntent().getStringExtra(EXTRA_REQUEST_ID);
        try {
            request = new JSONObject(getIntent().getStringExtra(EXTRA_REQUEST_JSON));
            transactionHash = transactionHash(request);
        } catch (Exception failure) {
            finishFailure("The agent request could not be validated.");
            return;
        }
        buildUi();
        showConfirmation();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        root.setBackgroundColor(0xFF0E1224);
        TextView title = new TextView(this);
        title.setText("Approve direct agent run");
        title.setTextSize(22);
        title.setTextColor(0xFFE8EAF6);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView detail = new TextView(this);
        String agent = request.optString("agent", "agent");
        String input = request.optString("input", "");
        detail.setText("Agent: " + agent + "\n\nExact request:\n" + input
                + "\n\nThis will authorize one agents.run request only. A fresh biometric signature is required. The authorization expires after 120 seconds and cannot be reused.");
        detail.setTextSize(14);
        detail.setTextColor(0xFFE8EAF6);
        detail.setPadding(0, dp(14), 0, dp(14));
        root.addView(detail);
        status = new TextView(this);
        status.setText("Waiting for your approval. No agent request has been sent.");
        status.setTextSize(12);
        status.setTextColor(0xFFADB4D0);
        root.addView(status);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private void showConfirmation() {
        new AlertDialog.Builder(this)
                .setTitle("Authorize " + request.optString("agent", "agent") + " agent")
                .setMessage("Review this exact request payload:\\n\\n" + request.toString()
                        + "\\n\\nThis approval authorizes this single transaction only. A fresh strong biometric signature is required; no request is sent if you cancel.")
                .setNegativeButton("Cancel", (dialog, which) ->
                        finishFailure("Owner cancelled the agent run."))
                .setPositiveButton("Approve and continue", (dialog, which) -> beginChallenge())
                .setOnCancelListener(dialog -> finishFailure("Owner cancelled the agent run."))
                .show();
    }

    private void beginChallenge() {
        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            finishFailure("Strong biometric authentication is unavailable. No agent request was sent.");
            return;
        }
        status.setText("Refreshing general authorization and requesting a one-use L2 challenge…");
        new Thread(() -> {
            try {
                String generalToken = OwnerSessionProvider.getGeneralToken(this);
                if (generalToken == null) {
                    throw new IllegalStateException("No active general owner grant is available.");
                }
                JSONObject body = new JSONObject();
                body.put("auth_level", "L2");
                body.put("scope", "agents.run");
                body.put("transaction_hash", transactionHash);
                JSONObject challenge = postJson(
                        ApiEndpoints.UOSA_ELEVATION_CHALLENGES, body, generalToken);
                String message = challenge.getString("message");
                runOnUiThread(() -> authenticateAndSign(
                        challenge.getString("challenge_id"), message));
            } catch (Exception failure) {
                runOnUiThread(() -> finishFailure(
                        "Could not request agent authorization: " + safeMessage(failure)));
            }
        }, "Amiki-Agent-L2-Challenge").start();
    }

    private void authenticateAndSign(String challengeId, String message) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            String activeAlias = getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                    .getString("owner_key_alias", OwnerSecurityActivity.KEY_ALIAS);
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(activeAlias, null);
            if (privateKey == null) {
                throw new IllegalStateException("The active owner signing key is unavailable.");
            }
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            BiometricPrompt prompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(
                        @NonNull BiometricPrompt.AuthenticationResult result) {
                    Signature unlocked = result.getCryptoObject() == null
                            ? null : result.getCryptoObject().getSignature();
                    if (unlocked == null) {
                        finishFailure("Biometric verification returned no signing operation.");
                        return;
                    }
                    try {
                        unlocked.update(message.getBytes(StandardCharsets.UTF_8));
                        String signature = Base64.encodeToString(unlocked.sign(), Base64.NO_WRAP);
                        completeElevation(challengeId, signature);
                    } catch (Exception failure) {
                        finishFailure("The agent authorization challenge could not be signed.");
                    }
                }

                @Override public void onAuthenticationError(int code, @NonNull CharSequence error) {
                    finishFailure("Biometric approval was cancelled or failed: " + error);
                }

                @Override public void onAuthenticationFailed() {
                    status.setText("Biometric not recognized. Try again or cancel.");
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Approve one direct agent run")
                    .setSubtitle(request.optString("agent", "agent") + " · agents.run")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build();
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(signer));
        } catch (Exception failure) {
            finishFailure("Could not prepare biometric agent approval: " + safeMessage(failure));
        }
    }

    private void completeElevation(String challengeId, String signature) {
        runOnUiThread(() -> status.setText("Biometric accepted. Exchanging the single-use L2 grant…"));
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("challenge_id", challengeId);
                body.put("signature", signature);
                JSONObject grant = postJson(ApiEndpoints.UOSA_ELEVATED_SESSIONS, body, null);
                JSONArray scopes = grant.optJSONArray("scopes");
                boolean hasAgentScope = false;
                if (scopes != null) {
                    for (int i = 0; i < scopes.length(); i++) {
                        if ("agents.run".equals(scopes.optString(i))) hasAgentScope = true;
                    }
                }
                if (!"L2".equals(grant.optString("auth_level"))
                        || !grant.optBoolean("single_use", false)
                        || !hasAgentScope
                        || !transactionHash.equals(grant.optString("transaction_hash"))) {
                    throw new IllegalStateException("The backend returned an L2 grant that does not match this exact agent request.");
                }
                UosaAgentAuthorization.complete(requestId,
                        grant.getString("access_token"), null);
                completed = true;
                runOnUiThread(this::finish);
            } catch (Exception failure) {
                finishFailure("Could not complete agent authorization: " + safeMessage(failure));
            }
        }, "Amiki-Agent-L2-Completion").start();
    }

    private JSONObject postJson(String endpoint, JSONObject body, String bearerToken) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bytes);
            }
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readLimited(stream, 16384);
            if (code < 200 || code >= 300) {
                String detail = "request rejected";
                try { detail = new JSONObject(response).optString("detail", detail); }
                catch (Exception ignored) { }
                throw new java.io.IOException("Backend HTTP " + code + ": " + detail);
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String transactionHash(JSONObject payload) throws Exception {
        String canonical = canonicalJson(payload);
        String material = "POST\n/api/v1/agents/run\n" + canonical;
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte value : digest) out.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        return out.toString();
    }

    private static String canonicalJson(Object value) throws Exception {
        if (value == null || value == JSONObject.NULL) return "null";
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder out = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) out.append(",");
                String key = keys.get(i);
                out.append(JSONObject.quote(key)).append(":")
                        .append(canonicalJson(object.get(key)));
            }
            return out.append("}").toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) out.append(",");
                out.append(canonicalJson(array.get(i)));
            }
            return out.append("]").toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        return value.toString();
    }

    private String readLimited(InputStream stream, int limit) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
                if (out.length() > limit) throw new IllegalStateException("Backend response exceeded safe limit.");
            }
        }
        return out.toString();
    }

    private void finishFailure(String message) {
        if (completed) return;
        completed = true;
        UosaAgentAuthorization.complete(requestId, null, message);
        runOnUiThread(this::finish);
    }

    private String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        if (!completed && requestId != null) {
            finishFailure("Agent approval was interrupted. The agent request was not submitted.");
        }
        super.onDestroy();
    }
}
