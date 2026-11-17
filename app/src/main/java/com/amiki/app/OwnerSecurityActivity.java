package com.amiki.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyInfo;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.Executor;


public final class OwnerSecurityActivity extends FragmentActivity {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "amiki.uosa.owner-signing.v1";
    private static final String ACTIVE_KEY_ALIAS_PREF = "owner_key_alias";
    private static final String CONTINUITY_KEY_ALIAS = "amiki.uosa.continuity.v1";
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;
    // Explicit Android-client scope profile. L2 scopes remain transaction-bound
    // and require a fresh biometric signature for each protected operation.
    private static final String[] INITIAL_SCOPES = new String[] {
            "ale.review.read",
            "ale.review.approve",
            "ale.review.reject",
            "ale.progress.read",
            "ale.progress.write",
            "ale.projects.read",
            "ale.projects.write",
            "ale.knowledge.read",
            "ale.claims.analyze",
            "ale.claims.review",
            "ale.claims.evaluate",
            "ale.claims.extract",
            "academic.read",
            "academic.write",
            "agents.run",
            "chat.use",
            "conversations.read",
            "conversations.write",
            "cipher.inspect",
            "device.read",
            "device.plan",
            "device.execute",
            "memory.read",
            "memory.session.clear",
            "memory.persistent.clear",
            "settings.read",
            "settings.write",
            "observability.read",
            "workspace.read",
            "workspace.proposals.write",
            "workspace.project.create",
            "workspace.change.apply",
            "workspace.file.write",
            "workspace.file.execute",
            "workspace.command.execute",
            "cloud.autonomy.read",
            "cloud.autonomy.stop",
            "cloud.autonomy.resume",
            "cloud.jobs.read",
            "cloud.jobs.cancel",
            "cloud.jobs.create",
            "cloud.jobs.run",
            "cloud.goals.read",
            "cloud.goals.pause",
            "cloud.goals.create",
            "cloud.goals.resume",
            "cloud.goals.update",
            "cloud.schedules.create",
            "cloud.schedules.manage",
            "cloud.monitor.read",
            "cloud.monitor.acknowledge",
            "cloud.outbox.inspect",
            "cloud.outbox.retry",
            "cloud.outbox.restart",
            "uosa.device.read",
            "uosa.security.audit.read"
    };

    private TextView capability;
    private TextView keyState;
    private TextView backendState;
    private TextView result;
    private EditText setupCodeInput;
    private Button createKeyButton;
    private Button enrollBackendButton;
    private Button generalGrantButton;
    private Button privilegedSessionButton;
    private Button revokeGeneralButton;
    private Button signButton;
    private Executor executor;
    private String deviceId;
    private boolean operationInFlight;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        executor = ContextCompat.getMainExecutor(this);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE);
        deviceId = getOrCreateDeviceId();
        buildUi();
        refreshState();
    }

    private void buildUi() {
        int background = 0xFF0E1224;
        int foreground = 0xFFE8EAF6;
        int muted = 0xFFADB4D0;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(24));
        root.setBackgroundColor(background);

        TextView title = label("Owner Security", 24, foreground, true);
        root.addView(title);
        TextView explanation = label(
                "UOSA device identity. Android protects the private signing key; biometric data never leaves this device. Server challenges are signed locally after a fresh strong biometric prompt.",
                13, muted, false);
        explanation.setPadding(0, dp(8), 0, dp(16));
        root.addView(explanation);

        capability = label("Checking strong biometric capability…", 13, foreground, false);
        keyState = label("Checking device signing key…", 13, foreground, false);
        backendState = label("Checking backend enrollment and session state…", 13, foreground, false);
        result = label("No backend enrollment or session has been created.", 12, muted, false);
        root.addView(section("DEVICE CAPABILITY"));
        root.addView(capability);
        root.addView(section("OWNER SIGNING KEY"));
        root.addView(keyState);

        createKeyButton = actionButton("Create device signing key");
        createKeyButton.setOnClickListener(v -> createKey());
        root.addView(createKeyButton);

        root.addView(section("FIRST DEVICE ENROLLMENT"));
        root.addView(label(
                "Generate a one-time setup code in Termux with: python scripts/create_uosa_setup_code.py. The code expires after 15 minutes and can be used once.",
                12, muted, false));
        setupCodeInput = new EditText(this);
        setupCodeInput.setSingleLine(true);
        setupCodeInput.setSaveEnabled(false);
        setupCodeInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        setupCodeInput.setHint("Paste one-time UOSA setup code");
        setupCodeInput.setTextColor(foreground);
        setupCodeInput.setHintTextColor(muted);
        root.addView(setupCodeInput, new LinearLayout.LayoutParams(-1, dp(52)));

        enrollBackendButton = actionButton("Enroll this device with Amiki");
        enrollBackendButton.setOnClickListener(v -> enrollBackend());
        root.addView(enrollBackendButton);

        root.addView(section("EXISTING OWNER RECOVERY"));
        root.addView(label("If you have your saved UOSA recovery codes, use the recovery ceremony to replace this device identity. Recovery revokes every previously enrolled device and session.", 12, muted, false));
        Button recoveryButton = actionButton("Recover existing owner account");
        recoveryButton.setOnClickListener(v -> startActivity(new android.content.Intent(this, UosaRecoveryActivity.class)));
        root.addView(recoveryButton);

        generalGrantButton = actionButton("Create persistent general access");
        generalGrantButton.setOnClickListener(v -> createGeneralGrant());
        root.addView(generalGrantButton);

        privilegedSessionButton = actionButton("Create 15-minute Cipher / ALE / Cloud session");
        privilegedSessionButton.setOnClickListener(v -> createPrivilegedSession());
        root.addView(privilegedSessionButton);

        revokeGeneralButton = actionButton("Revoke persistent general access");
        revokeGeneralButton.setOnClickListener(v -> revokeGeneralGrant());
        root.addView(revokeGeneralButton);

        signButton = actionButton("Test local biometric signing");
        signButton.setOnClickListener(v -> beginSigning());
        root.addView(signButton);

        root.addView(section("BACKEND SECURITY STATE"));
        root.addView(backendState);
        root.addView(section("SECURITY BOUNDARY"));
        TextView boundary = label(
                "The backend verifies P-256 signatures over one-time challenges. First enrollment uses a single-use setup code, not the retired owner bearer. L1 sessions last 15 minutes. Higher-risk actions require a fresh, transaction-bound L2 biometric signature. Cipher and Cipher X retain separate security boundaries; this Core session does not grant Cipher X L3 authority.",
                12, muted, false);
        root.addView(boundary);
        root.addView(result);

        Button back = actionButton("Back to Privacy & Security");
        back.setOnClickListener(v -> finish());
        root.addView(back);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    private TextView section(String value) {
        TextView view = label(value, 10, 0xFF8B7CFF, true);
        view.setPadding(0, dp(18), 0, dp(7));
        return view;
    }

    private TextView label(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(null, android.graphics.Typeface.BOLD);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button actionButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setAllCaps(false);
        button.setTextColor(0xFFE8EAF6);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF38305F));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(52));
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private boolean hasStrongBiometric() {
        return BiometricManager.from(this).canAuthenticate(AUTHENTICATORS)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    private KeyStore loadKeyStore() throws Exception {
        KeyStore store = KeyStore.getInstance(KEYSTORE);
        store.load(null);
        return store;
    }

    private boolean hasKey() {
        try {
            return loadKeyStore().containsAlias(activeKeyAlias());
        } catch (Exception ignored) {
            return false;
        }
    }

    private String activeKeyAlias() {
        return getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                .getString(ACTIVE_KEY_ALIAS_PREF, KEY_ALIAS);
    }

    private String getOrCreateDeviceId() {
        android.content.SharedPreferences prefs = getSharedPreferences(
                "amiki_uosa_device", MODE_PRIVATE);
        String existing = prefs.getString("device_id", null);
        if (existing != null && !existing.trim().isEmpty()) return existing;
        String generated = UUID.randomUUID().toString();
        prefs.edit().putString("device_id", generated).apply();
        return generated;
    }

    private void refreshState() {
        int status = BiometricManager.from(this).canAuthenticate(AUTHENTICATORS);
        capability.setText(status == BiometricManager.BIOMETRIC_SUCCESS
                ? "Strong biometric authentication is available."
                : "Strong biometric authentication is unavailable. Android status: " + biometricStatus(status));
        boolean enrolled = hasKey();
        keyState.setText(enrolled
                ? "A device-bound P-256 signing key is present in Android Keystore."
                : "No Amiki owner signing key is enrolled on this device.");
        createKeyButton.setEnabled(!operationInFlight
                && status == BiometricManager.BIOMETRIC_SUCCESS && !enrolled);
        createKeyButton.setText(enrolled ? "Signing key already created" : "Create device signing key");
        boolean registered = isBackendEnrolled();
        enrollBackendButton.setEnabled(!operationInFlight && status == BiometricManager.BIOMETRIC_SUCCESS
                && enrolled && !registered);
        generalGrantButton.setEnabled(!operationInFlight && status == BiometricManager.BIOMETRIC_SUCCESS
                && enrolled && registered);
        privilegedSessionButton.setEnabled(!operationInFlight && status == BiometricManager.BIOMETRIC_SUCCESS
                && enrolled && registered);
        revokeGeneralButton.setEnabled(!operationInFlight && OwnerSessionVault.generalGrantId(this) != null);
        signButton.setEnabled(!operationInFlight && status == BiometricManager.BIOMETRIC_SUCCESS
                && enrolled);
        backendState.setText("General: "
                + (OwnerSessionVault.generalGrantId(this) != null
                    ? (OwnerSessionVault.hasGeneralToken(this) ? "access token active; renewable grant stored"
                        : "persistent grant stored; access token will renew on next request")
                    : "no persistent grant")
                + "\\nPrivileged Cipher/ALE/Cloud: "
                + (OwnerSessionVault.hasPrivilegedToken(this)
                    ? "15-minute session active until " + OwnerSessionVault.privilegedExpiresAt(this)
                    : "no active 15-minute session")
                + (registered ? "" : "\\nThis device is not enrolled with Core.");
    }

    private boolean isBackendEnrolled() {
        return getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                .getBoolean("backend_enrolled", false);
    }

    private void markBackendEnrolled() {
        getSharedPreferences("amiki_uosa_device", MODE_PRIVATE)
                .edit().putBoolean("backend_enrolled", true).apply();
    }

    private void showRecoveryCodes(JSONArray codes) {
        if (codes == null || codes.length() == 0) {
            result.setText("Enrollment succeeded, but no recovery codes were returned. Check the backend response before relying on recovery.");
            return;
        }
        StringBuilder formatted = new StringBuilder();
        for (int i = 0; i < codes.length(); i++) {
            String code = codes.optString(i, "");
            if (!code.isEmpty()) formatted.append(i + 1).append(". ").append(code).append("\n");
        }
        String recoveryText = formatted.toString().trim();
        TextView codeView = label(recoveryText, 13, 0xFFE8EAF6, false);
        codeView.setTextIsSelectable(true);
        new AlertDialog.Builder(this)
                .setTitle("Save your UOSA recovery codes")
                .setMessage("These codes are displayed only once. Save them offline in a secure place. Do not send them to anyone.")
                .setView(codeView)
                .setPositiveButton("Copy codes", (dialog, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Amiki UOSA recovery codes", recoveryText));
                        Toast.makeText(this, "Recovery codes copied. Store them offline securely.", Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private String biometricStatus(int status) {
        switch (status) {
            case BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE: return "no biometric hardware";
            case BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE: return "hardware unavailable";
            case BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED: return "no strong biometric enrolled";
            case BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED: return "security update required";
            case BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED: return "strong biometric unsupported";
            case BiometricManager.BIOMETRIC_STATUS_UNKNOWN: return "status unknown";
            default: return "status " + status;
        }
    }

    private void createKey() {
        if (!hasStrongBiometric() || hasKey()) {
            refreshState();
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    activeKeyAlias(), KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(true)
                    .setUserAuthenticationValidityDurationSeconds(-1)
                    .setInvalidatedByBiometricEnrollment(true)
                    .build();
            generator.initialize(spec);
            java.security.KeyPair pair = generator.generateKeyPair();
            KeyInfo keyInfo = java.security.KeyFactory.getInstance(
                    pair.getPrivate().getAlgorithm(), KEYSTORE)
                    .getKeySpec(pair.getPrivate(), KeyInfo.class);
            boolean hardwareBacked = keyInfo.isInsideSecureHardware();
            boolean authEnforcedByHardware = android.os.Build.VERSION.SDK_INT < 29
                    || keyInfo.isUserAuthenticationRequirementEnforcedBySecureHardware();
            if (!hardwareBacked || !authEnforcedByHardware) {
                loadKeyStore().deleteEntry(activeKeyAlias());
                result.setText("This device did not provide a hardware-backed key with hardware-enforced biometric authentication. The key was discarded; UOSA enrollment is unavailable on this configuration.");
                refreshState();
                return;
            }
            String fingerprint = fingerprint(pair.getPublic().getEncoded());
            result.setText("Hardware-backed device signing key created. Public-key SHA-256: " + fingerprint
                    + "\nThe private key remains non-exportable; authentication is enforced by secure hardware.");
            refreshState();
        } catch (Exception failure) {
            result.setText("Could not create the device signing key: " + safeMessage(failure));
        }
    }

    private void enrollBackend() {
        if (!hasKey() || operationInFlight || isBackendEnrolled()) return;
        final String setupCode = setupCodeInput == null || setupCodeInput.getText() == null
                ? "" : setupCodeInput.getText().toString().trim();
        if (setupCode.length() < 32 || setupCode.length() > 100) {
            if (setupCodeInput != null) setupCodeInput.setError("Enter the one-time setup code from Termux.");
            return;
        }
        try {
            java.security.cert.Certificate certificate = loadKeyStore().getCertificate(activeKeyAlias());
            if (certificate == null) throw new IllegalStateException("The device public key is unavailable.");
            String publicKey = Base64.encodeToString(certificate.getPublicKey().getEncoded(), Base64.NO_WRAP);
            JSONObject request = new JSONObject();
            request.put("setup_code", setupCode);
            request.put("device_id", deviceId);
            request.put("label", "Amiki Android device");
            request.put("public_key", publicKey);
            startOperation("Requesting the one-time first-owner enrollment challenge…");
            new Thread(() -> {
                try {
                    JSONObject challenge = postJson(
                            ApiEndpoints.UOSA_SETUP_ENROLLMENT_CHALLENGES, request, null);
                    String message = challenge.getString("message");
                    runOnUiThread(() -> authenticateAndSign(message,
                            "Enroll this device as the Amiki owner", signature -> new Thread(() -> {
                                try {
                                    JSONObject complete = new JSONObject();
                                    complete.put("challenge_id", challenge.getString("challenge_id"));
                                    complete.put("signature", signature);
                                    JSONObject enrolled = postJson(
                                            ApiEndpoints.UOSA_SETUP_ENROLL, complete, null);
                                    markBackendEnrolled();
                                    JSONArray recoveryCodes = enrolled.optJSONArray("recovery_codes");
                                    runOnUiThread(() -> {
                                        setupCodeInput.setText("");
                                        finishOperation(
                                                "First owner device enrolled. Key fingerprint: "
                                                        + enrolled.optString("key_fingerprint", "verified")
                                                        + ". Save the one-time recovery codes now.");
                                        showRecoveryCodes(recoveryCodes);
                                    });
                                } catch (Exception failure) {
                                    runOnUiThread(() -> finishOperation(
                                            "Device enrollment failed: " + safeMessage(failure)));
                                }
                            }).start()));
                } catch (Exception failure) {
                    runOnUiThread(() -> finishOperation(
                            "Could not request the first-owner challenge: " + safeMessage(failure)));
                }
            }).start();
        } catch (Exception failure) {
            result.setText("Could not prepare first-owner enrollment: " + safeMessage(failure));
            refreshState();
        }
    }

    private JSONArray scopeProfile(boolean privileged) {
        JSONArray scopes = new JSONArray();
        for (String scope : INITIAL_SCOPES) {
            boolean isPrivileged = scope.startsWith("cipher.")
                    || scope.startsWith("ale.") || scope.startsWith("cloud.");
            if (isPrivileged == privileged) scopes.put(scope);
        }
        return scopes;
    }

    private void createGeneralGrant() {
        if (!hasKey() || operationInFlight || !isBackendEnrolled()) return;
        startOperation("Preparing a separate hardware-backed continuity key…");
        new Thread(() -> {
            try {
                String continuityPublicKey = ensureContinuityKey();
                JSONObject request = new JSONObject();
                request.put("device_id", deviceId);
                request.put("audience", "amiki-core");
                request.put("scopes", scopeProfile(false));
                request.put("continuity_public_key", continuityPublicKey);
                JSONObject challenge = postJson(
                        ApiEndpoints.UOSA_GENERAL_GRANT_CHALLENGES, request, null);
                String message = challenge.getString("message");
                runOnUiThread(() -> authenticateAndSign(message,
                        "Approve persistent general capability access", signature -> new Thread(() -> {
                            try {
                                JSONObject complete = new JSONObject();
                                complete.put("challenge_id", challenge.getString("challenge_id"));
                                complete.put("signature", signature);
                                JSONObject grant = postJson(ApiEndpoints.UOSA_GENERAL_GRANTS, complete, null);
                                if (!"general".equals(grant.optString("session_class"))
                                        || !grant.optBoolean("grant_persistent", false)) {
                                    throw new IllegalStateException("Backend did not confirm a persistent general grant.");
                                }
                                OwnerSessionVault.saveGeneralToken(this,
                                        grant.getString("access_token"),
                                        grant.getLong("expires_at"),
                                        grant.getString("grant_id"));
                                runOnUiThread(() -> finishOperation(
                                        "Persistent general access is active. Its short-lived token renews silently using the separate device continuity key. Scopes: "
                                                + grant.optJSONArray("scopes")));
                            } catch (Exception failure) {
                                runOnUiThread(() -> finishOperation(
                                        "General authorization failed: " + safeMessage(failure)));
                            }
                        }).start()));
            } catch (Exception failure) {
                runOnUiThread(() -> finishOperation(
                        "Could not prepare general authorization: " + safeMessage(failure)));
            }
        }, "amiki-uosa-general-grant").start();
    }

    private void createPrivilegedSession() {
        if (!hasKey() || operationInFlight || !isBackendEnrolled()) return;
        JSONObject request = new JSONObject();
        try {
            request.put("device_id", deviceId);
            request.put("audience", "amiki-core");
            request.put("scopes", scopeProfile(true));
        } catch (Exception failure) {
            finishOperation("Could not prepare privileged session request: " + safeMessage(failure));
            return;
        }
        startOperation("Requesting a separate 15-minute Cipher/ALE/Cloud session challenge…");
        new Thread(() -> {
            try {
                JSONObject challenge = postJson(ApiEndpoints.UOSA_CHALLENGES, request, null);
                String message = challenge.getString("message");
                runOnUiThread(() -> authenticateAndSign(message,
                        "Approve 15-minute privileged capability access", signature -> new Thread(() -> {
                            try {
                                JSONObject complete = new JSONObject();
                                complete.put("challenge_id", challenge.getString("challenge_id"));
                                complete.put("signature", signature);
                                JSONObject session = postJson(ApiEndpoints.UOSA_SESSIONS, complete, null);
                                if (!"privileged".equals(session.optString("session_class"))
                                        || session.optInt("expires_in_seconds", 0) != 900) {
                                    throw new IllegalStateException("Backend did not issue the expected privileged 15-minute session.");
                                }
                                OwnerSessionVault.savePrivilegedToken(this,
                                        session.getString("access_token"), session.getLong("expires_at"));
                                runOnUiThread(() -> finishOperation(
                                        "Privileged session issued. Cipher, ALE and Cloud access expires in 15 minutes. General capabilities remain on their separate renewable grant."));
                            } catch (Exception failure) {
                                runOnUiThread(() -> finishOperation(
                                        "Privileged session creation failed: " + safeMessage(failure)));
                            }
                        }).start()));
            } catch (Exception failure) {
                runOnUiThread(() -> finishOperation(
                        "Could not request privileged session challenge: " + safeMessage(failure)));
            }
        }, "amiki-uosa-privileged-session").start();
    }

    private String ensureContinuityKey() throws Exception {
        KeyStore store = loadKeyStore();
        if (!store.containsAlias(CONTINUITY_KEY_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    CONTINUITY_KEY_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(new java.security.spec.ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .setUserAuthenticationRequired(false)
                    .build();
            generator.initialize(spec);
            java.security.KeyPair pair = generator.generateKeyPair();
            KeyInfo info = java.security.KeyFactory.getInstance(
                    pair.getPrivate().getAlgorithm(), KEYSTORE)
                    .getKeySpec(pair.getPrivate(), KeyInfo.class);
            if (!info.isInsideSecureHardware()) {
                store.deleteEntry(CONTINUITY_KEY_ALIAS);
                throw new IllegalStateException(
                        "This device did not provide a hardware-backed continuity key. Persistent general access is disabled rather than using a software key.");
            }
        }
        java.security.cert.Certificate certificate = loadKeyStore().getCertificate(CONTINUITY_KEY_ALIAS);
        if (certificate == null) throw new IllegalStateException("Continuity public key is unavailable.");
        PrivateKey privateKey = (PrivateKey) loadKeyStore().getKey(CONTINUITY_KEY_ALIAS, null);
        KeyInfo info = java.security.KeyFactory.getInstance(privateKey.getAlgorithm(), KEYSTORE)
                .getKeySpec(privateKey, KeyInfo.class);
        if (!info.isInsideSecureHardware() || info.isUserAuthenticationRequired()) {
            throw new IllegalStateException("The continuity key must be hardware-backed and separate from biometric owner approval.");
        }
        return Base64.encodeToString(certificate.getPublicKey().getEncoded(), Base64.NO_WRAP);
    }

    private void revokeGeneralGrant() {
        if (operationInFlight || OwnerSessionVault.generalGrantId(this) == null) return;
        startOperation("Revoking persistent general access for this device…");
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String token = OwnerSessionProvider.getGeneralToken(this);
                if (token == null) throw new IllegalStateException("No active general grant session is available to revoke.");
                connection = (HttpURLConnection) new java.net.URL(
                        ApiEndpoints.UOSA_GENERAL_GRANT_CURRENT).openConnection();
                connection.setRequestMethod("DELETE");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                int code = connection.getResponseCode();
                if (code < 200 || code >= 300) throw new java.io.IOException("Backend returned HTTP " + code + ".");
                OwnerSessionVault.clearGeneral(this);
                KeyStore store = loadKeyStore();
                if (store.containsAlias(CONTINUITY_KEY_ALIAS)) store.deleteEntry(CONTINUITY_KEY_ALIAS);
                runOnUiThread(() -> finishOperation("Persistent general grant revoked. Chat, agents and other general capabilities will require owner re-authorization."));
            } catch (Exception failure) {
                runOnUiThread(() -> finishOperation("Could not revoke persistent general access: " + safeMessage(failure)));
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "amiki-uosa-revoke-general-grant").start();
    }

    private interface SignedChallengeCallback {
        void onSigned(String signatureBase64);
    }

    private void authenticateAndSign(
            String message, String promptSubtitle, SignedChallengeCallback callback) {
        try {
            java.security.PrivateKey privateKey =
                    (java.security.PrivateKey) loadKeyStore().getKey(activeKeyAlias(), null);
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(privateKey);
            BiometricPrompt prompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                @NonNull BiometricPrompt.AuthenticationResult authenticationResult) {
                            Signature unlocked = authenticationResult.getCryptoObject() == null
                                    ? null : authenticationResult.getCryptoObject().getSignature();
                            if (unlocked == null) {
                                finishOperation("Biometric succeeded but no signing operation was returned.");
                                return;
                            }
                            try {
                                unlocked.update(message.getBytes(StandardCharsets.UTF_8));
                                byte[] signed = unlocked.sign();
                                callback.onSigned(Base64.encodeToString(signed, Base64.NO_WRAP));
                            } catch (Exception failure) {
                                finishOperation("Challenge signing failed: " + safeMessage(failure));
                            }
                        }

                        @Override
                        public void onAuthenticationError(int code, @NonNull CharSequence message) {
                            finishOperation("Biometric authentication cancelled or failed: " + message);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            result.setText("Biometric not recognized. Try again or cancel.");
                        }
                    });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Verify owner presence")
                    .setSubtitle(promptSubtitle)
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build();
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(signature));
        } catch (Exception failure) {
            finishOperation("Could not prepare the signing operation: " + safeMessage(failure));
        }
    }

    private void beginSigning() {
        if (!hasStrongBiometric() || !hasKey() || operationInFlight) {
            refreshState();
            return;
        }
        byte[] nonce = new byte[32];
        new SecureRandom().nextBytes(nonce);
        String message = "AMIKI-UOSA-LOCAL-TEST-v1:"
                + Base64.encodeToString(nonce, Base64.NO_WRAP);
        startOperation("Waiting for biometric authentication…");
        authenticateAndSign(message, "Authorize one local signing test", signature ->
                finishOperation("Biometric accepted. Local ECDSA signature created ("
                        + signature.length() + " Base64 characters). No backend permission was granted."));
    }

    private JSONObject postJson(String endpoint, JSONObject body, String bearerToken) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(12000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (bearerToken != null && !bearerToken.trim().isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            }
            byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(requestBytes);
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readBody(stream);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Backend returned HTTP " + status
                        + (response.length() > 0 ? ": " + safeErrorDetail(response) : ""));
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String safeErrorDetail(String response) {
        try {
            return new JSONObject(response).optString("detail", "request rejected");
        } catch (Exception ignored) {
            return "request rejected";
        }
    }

    private String readBody(InputStream input) throws Exception {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
                if (body.length() > 64 * 1024) throw new Exception("Backend response exceeded safe limit.");
            }
        }
        return body.toString();
    }

    private void startOperation(String message) {
        operationInFlight = true;
        result.setText(message);
        refreshState();
    }

    private void finishOperation(String message) {
        operationInFlight = false;
        result.setText(message);
        refreshState();
        if (message.startsWith("UOSA L1 session issued")) {
            Toast.makeText(this, "Owner session created.", Toast.LENGTH_SHORT).show();
        }
    }

    private String fingerprint(byte[] encoded) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(encoded);
        return Base64.encodeToString(digest, Base64.NO_WRAP);
    }

    private String safeMessage(Exception failure) {
        String message = failure.getMessage();
        return message == null || message.trim().isEmpty()
                ? failure.getClass().getSimpleName() : message;
    }
}
