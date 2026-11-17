package com.amiki.app;

import android.app.Activity;
import android.os.Bundle;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.security.keystore.KeyInfo;
import android.util.Base64;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.fragment.app.FragmentActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.SecureRandom;
import java.util.concurrent.Executor;


public final class OwnerSecurityActivity extends FragmentActivity {
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "amiki.uosa.owner-signing.v1";
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_STRONG;

    private TextView capability;
    private TextView keyState;
    private TextView result;
    private Button enrollButton;
    private Button signButton;
    private Executor executor;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        executor = ContextCompat.getMainExecutor(this);
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
                "UOSA device identity foundation. Android protects the private signing key; biometric data never leaves this device.",
                13, muted, false);
        explanation.setPadding(0, dp(8), 0, dp(16));
        root.addView(explanation);

        capability = label("Checking strong biometric capability…", 13, foreground, false);
        keyState = label("Checking device signing key…", 13, foreground, false);
        result = label("No authentication or backend session has been created.", 12, muted, false);
        root.addView(section("DEVICE CAPABILITY"));
        root.addView(capability);
        root.addView(section("OWNER SIGNING KEY"));
        root.addView(keyState);

        enrollButton = actionButton("Create device signing key");
        enrollButton.setOnClickListener(v -> createKey());
        root.addView(enrollButton);

        signButton = actionButton("Test biometric signing");
        signButton.setOnClickListener(v -> beginSigning());
        root.addView(signButton);

        root.addView(section("SECURITY BOUNDARY"));
        TextView boundary = label(
                "This screen only proves that this device can use its enrolled key after a strong biometric prompt. The signature is local test data. Backend challenge verification, scoped sessions, ALE migration, Cipher authorization and Cipher X authorization are not implemented by this screen.",
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
            return loadKeyStore().containsAlias(KEY_ALIAS);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void refreshState() {
        int status = BiometricManager.from(this).canAuthenticate(AUTHENTICATORS);
        capability.setText(status == BiometricManager.BIOMETRIC_SUCCESS
                ? "Strong biometric authentication is available."
                : "Strong biometric authentication is unavailable. Android status: " + biometricStatus(status));
        boolean enrolled = hasKey();
        keyState.setText(enrolled
                ? "A device-bound signing key is present in Android Keystore."
                : "No Amiki owner signing key is enrolled on this device.");
        enrollButton.setEnabled(status == BiometricManager.BIOMETRIC_SUCCESS && !enrolled);
        enrollButton.setText(enrolled ? "Signing key already enrolled" : "Create device signing key");
        signButton.setEnabled(status == BiometricManager.BIOMETRIC_SUCCESS && enrolled);
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
        if (!hasStrongBiometric()) {
            refreshState();
            return;
        }
        if (hasKey()) {
            refreshState();
            return;
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    KEY_ALIAS, KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
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
                loadKeyStore().deleteEntry(KEY_ALIAS);
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

    private void beginSigning() {
        if (!hasStrongBiometric() || !hasKey()) {
            refreshState();
            return;
        }
        try {
            KeyStore store = loadKeyStore();
            java.security.PrivateKey privateKey =
                    (java.security.PrivateKey) store.getKey(KEY_ALIAS, null);
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
                                result.setText("Biometric succeeded but no signing operation was returned.");
                                return;
                            }
                            try {
                                byte[] nonce = new byte[32];
                                new SecureRandom().nextBytes(nonce);
                                byte[] domain = "AMIKI-UOSA-LOCAL-TEST-v1:".getBytes(StandardCharsets.UTF_8);
                                byte[] payload = new byte[domain.length + nonce.length];
                                System.arraycopy(domain, 0, payload, 0, domain.length);
                                System.arraycopy(nonce, 0, payload, domain.length, nonce.length);
                                unlocked.update(payload);
                                byte[] signed = unlocked.sign();
                                result.setText("Biometric accepted. Local signature created (" + signed.length
                                        + " bytes).\nThis is not a server challenge and grants no Amiki permissions.");
                                Toast.makeText(OwnerSecurityActivity.this,
                                        "Local biometric signing test passed.", Toast.LENGTH_SHORT).show();
                            } catch (Exception failure) {
                                result.setText("Signing failed: " + safeMessage(failure));
                            }
                        }

                        @Override
                        public void onAuthenticationError(int code, @NonNull CharSequence message) {
                            result.setText("Biometric authentication cancelled or failed: " + message);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            result.setText("Biometric not recognized. Try again or cancel.");
                        }
                    });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Verify owner presence")
                    .setSubtitle("Authorize one local signing operation")
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build();
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(signature));
        } catch (Exception failure) {
            result.setText("Could not prepare the signing operation: " + safeMessage(failure));
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
