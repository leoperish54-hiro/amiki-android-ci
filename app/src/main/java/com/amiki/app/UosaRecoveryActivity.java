package com.amiki.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.UUID;
import java.util.concurrent.Executor;

/** Existing-owner recovery. The saved recovery code is sent only in the challenge request. */
public final class UosaRecoveryActivity extends FragmentActivity {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String PENDING_ALIAS_PREF = "owner_recovery_pending_key_alias";
    private static final String ACTIVE_ALIAS_PREF = "owner_key_alias";
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private EditText recoveryCodeInput;
    private TextView status;
    private Button recoverButton;
    private Executor executor;
    private String deviceId;
    private String recoveryAlias;
    private boolean inFlight;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        executor = ContextCompat.getMainExecutor(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        deviceId = getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                .getString("device_id", null);
        if (deviceId == null || deviceId.trim().isEmpty()) {
            deviceId = UUID.randomUUID().toString();
            getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                    .edit().putString("device_id", deviceId).apply();
        }
        recoveryAlias = getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                .getString(PENDING_ALIAS_PREF, null);
        buildUi();
    }

    private void buildUi() {
        int fg = 0xFFE8EAF6, muted = 0xFFADB4D0;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(24));
        root.setBackgroundColor(0xFF0E1224);
        TextView title = new TextView(this);
        title.setText("Recover UOSA owner access");
        title.setTextSize(23);
        title.setTextColor(fg);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        TextView explanation = new TextView(this);
        explanation.setText("Enter one unused recovery code that you saved during owner enrollment. Amiki will create a new hardware-backed signing key and ask for biometric approval. Recovery revokes every previously enrolled device and all active sessions, then replaces the recovery codes.");
        explanation.setTextSize(13);
        explanation.setTextColor(muted);
        explanation.setPadding(0, dp(12), 0, dp(14));
        root.addView(explanation);
        recoveryCodeInput = new EditText(this);
        recoveryCodeInput.setSingleLine(true);
        recoveryCodeInput.setSaveEnabled(false);
        recoveryCodeInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        recoveryCodeInput.setHint("Paste one saved recovery code");
        recoveryCodeInput.setTextColor(fg);
        recoveryCodeInput.setHintTextColor(muted);
        root.addView(recoveryCodeInput, new LinearLayout.LayoutParams(-1, dp(54)));
        recoverButton = new Button(this);
        recoverButton.setText("Begin owner recovery");
        recoverButton.setAllCaps(false);
        recoverButton.setTextColor(fg);
        recoverButton.setOnClickListener(v -> beginRecovery());
        root.addView(recoverButton, new LinearLayout.LayoutParams(-1, dp(54)));
        status = new TextView(this);
        status.setText("Recovery has not started. Your recovery code is not stored by this screen.");
        status.setTextSize(12);
        status.setTextColor(muted);
        status.setPadding(0, dp(14), 0, dp(10));
        root.addView(status);
        Button back = new Button(this);
        back.setText("Cancel and return");
        back.setAllCaps(false);
        back.setOnClickListener(v -> finish());
        root.addView(back, new LinearLayout.LayoutParams(-1, dp(50)));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void beginRecovery() {
        if (inFlight) return;
        if (BiometricManager.from(this).canAuthenticate(AUTHENTICATORS)
                != BiometricManager.BIOMETRIC_SUCCESS) {
            status.setText("Strong biometric authentication is unavailable. Recovery cannot proceed on this device.");
            return;
        }
        final String recoveryCode = recoveryCodeInput.getText() == null
                ? "" : recoveryCodeInput.getText().toString().trim();
        if (recoveryCode.length() < 32 || recoveryCode.length() > 100) {
            recoveryCodeInput.setError("Enter one complete saved recovery code.");
            return;
        }
        inFlight = true;
        recoverButton.setEnabled(false);
        status.setText("Preparing a new hardware-backed signing key…");
        new Thread(() -> {
            try {
                String alias = ensureRecoveryKey();
                java.security.cert.Certificate certificate = loadKeyStore().getCertificate(alias);
                if (certificate == null) throw new IllegalStateException("Recovery public key is unavailable.");
                JSONObject request = new JSONObject();
                request.put("recovery_code", recoveryCode);
                request.put("device_id", deviceId);
                request.put("label", "Amiki Android recovered device");
                request.put("public_key", Base64.encodeToString(
                        certificate.getPublicKey().getEncoded(), Base64.NO_WRAP));
                JSONObject challenge = postJson(ApiEndpoints.UOSA_RECOVERY_CHALLENGES, request);
                String message = challenge.getString("message");
                runOnUiThread(() -> authenticateAndSign(alias, message, signature -> new Thread(() -> {
                    try {
                        JSONObject complete = new JSONObject();
                        complete.put("challenge_id", challenge.getString("challenge_id"));
                        complete.put("signature", signature);
                        JSONObject recovered = postJson(ApiEndpoints.UOSA_RECOVERY_COMPLETE, complete);
                        if (!"recovered".equals(recovered.optString("status"))
                                || !recovered.optBoolean("revoked_previous_devices", false)) {
                            throw new IllegalStateException("Backend did not confirm full owner recovery.");
                        }
                        String oldAlias = getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                                .getString(ACTIVE_ALIAS_PREF, "amiki.uosa.owner-signing.v1");
                        getSharedPreferences("amiki_uosa_device", MODE_PRIVATE).edit()
                                .putString(ACTIVE_ALIAS_PREF, alias)
                                .remove(PENDING_ALIAS_PREF)
                                .putBoolean("backend_enrolled", true)
                                .apply();
                        OwnerSessionVault.clear(this);
                        try {
                            KeyStore sessionKeys = loadKeyStore();
                            if (sessionKeys.containsAlias("amiki.uosa.continuity.v1")) {
                                sessionKeys.deleteEntry("amiki.uosa.continuity.v1");
                            }
                        } catch (Exception ignored) { }
                        if (oldAlias != null && !oldAlias.equals(alias)) {
                            try {
                                KeyStore store = loadKeyStore();
                                if (store.containsAlias(oldAlias)) store.deleteEntry(oldAlias);
                            } catch (Exception ignored) { }
                        }
                        JSONArray codes = recovered.optJSONArray("recovery_codes");
                        runOnUiThread(() -> {
                            recoveryCodeInput.setText("");
                            status.setText("Owner recovery completed. Previous devices and sessions were revoked. Create fresh capability sessions after saving the replacement recovery codes.");
                            showRecoveryCodes(codes);
                            inFlight = false;
                            recoverButton.setEnabled(false);
                        });
                    } catch (Exception failure) {
                        runOnUiThread(() -> finishFailure("Recovery completion failed: " + safeMessage(failure)));
                    }
                }).start()));
            } catch (Exception failure) {
                runOnUiThread(() -> finishFailure("Could not start recovery: " + safeMessage(failure)));
            }
        }).start();
    }

    private String ensureRecoveryKey() throws Exception {
        if (recoveryAlias != null && loadKeyStore().containsAlias(recoveryAlias)) return recoveryAlias;
        recoveryAlias = "amiki.uosa.owner-recovery." + UUID.randomUUID();
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(
                recoveryAlias, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(true)
                .setUserAuthenticationValidityDurationSeconds(-1)
                .setInvalidatedByBiometricEnrollment(true)
                .build());
        java.security.KeyPair pair = generator.generateKeyPair();
        KeyInfo info = java.security.KeyFactory.getInstance(pair.getPrivate().getAlgorithm(), KEYSTORE)
                .getKeySpec(pair.getPrivate(), KeyInfo.class);
        boolean hardware = info.isInsideSecureHardware();
        boolean authHardware = android.os.Build.VERSION.SDK_INT < 29
                || info.isUserAuthenticationRequirementEnforcedBySecureHardware();
        if (!hardware || !authHardware) {
            loadKeyStore().deleteEntry(recoveryAlias);
            recoveryAlias = null;
            throw new IllegalStateException("Device did not provide a hardware-backed key with hardware-enforced biometric authentication.");
        }
        getSharedPreferences("amiki_uosa_device", MODE_PRIVATE).edit()
                .putString(PENDING_ALIAS_PREF, recoveryAlias).apply();
        return recoveryAlias;
    }

    private KeyStore loadKeyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }

    private interface SignedCallback { void onSigned(String signatureBase64); }

    private void authenticateAndSign(String alias, String message, SignedCallback callback) {
        try {
            java.security.PrivateKey key = (java.security.PrivateKey) loadKeyStore().getKey(alias, null);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(key);
            BiometricPrompt prompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                @Override public void onAuthenticationSucceeded(
                        @NonNull BiometricPrompt.AuthenticationResult authResult) {
                    Signature unlocked = authResult.getCryptoObject() == null
                            ? null : authResult.getCryptoObject().getSignature();
                    if (unlocked == null) {
                        finishFailure("Biometric authentication returned no signing operation.");
                        return;
                    }
                    try {
                        unlocked.update(message.getBytes(StandardCharsets.UTF_8));
                        callback.onSigned(Base64.encodeToString(unlocked.sign(), Base64.NO_WRAP));
                    } catch (Exception failure) {
                        finishFailure("Could not sign recovery challenge: " + safeMessage(failure));
                    }
                }
                @Override public void onAuthenticationError(int code, @NonNull CharSequence error) {
                    finishFailure("Biometric recovery approval cancelled or failed: " + error);
                }
            });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Authorize UOSA owner recovery")
                    .setSubtitle("Replace owner identity and revoke previous devices")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build();
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(signature));
        } catch (Exception failure) {
            finishFailure("Could not prepare recovery signing: " + safeMessage(failure));
        }
    }

    private JSONObject postJson(String endpoint, JSONObject body) throws Exception {
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
            try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
            int statusCode = connection.getResponseCode();
            InputStream stream = statusCode >= 200 && statusCode < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readBody(stream);
            if (statusCode < 200 || statusCode >= 300) {
                String detail;
                try { detail = new JSONObject(response).optString("detail", "request rejected"); }
                catch (Exception ignored) { detail = "request rejected"; }
                throw new java.io.IOException("Backend HTTP " + statusCode + ": " + detail);
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String readBody(InputStream stream) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line);
                if (out.length() > 65536) throw new IllegalStateException("Backend response exceeded safe limit.");
            }
        }
        return out.toString();
    }

    private void showRecoveryCodes(JSONArray codes) {
        if (codes == null || codes.length() == 0) {
            status.setText("Recovery completed, but no replacement codes were returned. Contact the owner recovery procedure before relying on this account.");
            return;
        }
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < codes.length(); i++) {
            String code = codes.optString(i, "");
            if (!code.isEmpty()) formatted.append(i + 1).append(". ").append(code).append("\n");
        }
        String text = formatted.toString().trim();
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextIsSelectable(true);
        view.setTextColor(0xFFE8EAF6);
        new AlertDialog.Builder(this)
                .setTitle("Save replacement UOSA recovery codes")
                .setMessage("These codes are shown once. Store them offline in a secure place. Anyone with a code can recover the owner account.")
                .setView(view)
                .setPositiveButton("Copy codes", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Amiki UOSA recovery codes", text));
                        Toast.makeText(this, "Replacement codes copied. Store them offline securely.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void finishFailure(String message) {
        runOnUiThread(() -> {
            recoveryCodeInput.setText("");
            status.setText(message);
            inFlight = false;
            recoverButton.setEnabled(true);
        });
    }

    private String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }
}
