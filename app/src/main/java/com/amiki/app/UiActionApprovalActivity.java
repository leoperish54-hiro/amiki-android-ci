package com.amiki.app;

import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Base64;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.concurrent.Executor;

/** Explicit owner confirmation plus a fresh P-256 biometric signature for one UI action. */
public final class UiActionApprovalActivity extends FragmentActivity {
    static final String EXTRA_ACTION_ID = "com.amiki.app.extra.UI_ACTION_ID";
    private String actionId;
    private UiActionDispatcher.Request request;
    private TextView statusView;
    private boolean completed;
    private boolean inFlight;
    private BiometricPrompt biometricPrompt;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        actionId = getIntent().getStringExtra(EXTRA_ACTION_ID);
        request = AccessibilityOperatorService.pendingRequest(actionId);
        if (request == null || actionId == null) {
            finish();
            return;
        }
        showOwnerConfirmation();
    }

    private void showOwnerConfirmation() {
        StringBuilder summary = new StringBuilder();
        summary.append("App: ").append(request.packageName).append("\nAction: ");
        switch (request.actionType) {
            case TAP:
                summary.append("Tap the exact control: ").append(request.targetLabel);
                break;
            case TYPE_TEXT:
                summary.append("Enter this exact text:\n\n").append(request.typedText)
                        .append("\n\nField: ").append(request.targetViewId);
                break;
            case SCROLL_FORWARD: summary.append("Scroll forward in the one identified container"); break;
            case SCROLL_BACKWARD: summary.append("Scroll backward in the one identified container"); break;
            case BACK: summary.append("Navigate back from the current app window"); break;
            default: summary.append("Unsupported action");
        }
        summary.append("\n\nExpected result: ");
        if ("field_value_matches".equals(request.postconditionType)) {
            summary.append("the exact entered text is read back from the approved field");
        } else {
            summary.append("“").append(request.expectedUiText).append("” becomes ")
                    .append("text_visible".equals(request.postconditionType) ? "visible" : "absent");
        }
        summary.append("\nWindow: ").append(request.windowId)
                .append("\nSnapshot age: ").append(request.snapshotAgeMs).append(" ms")
                .append("\n\nThis approval is valid for this single action only.");
        statusView = new TextView(this);
        statusView.setText("Waiting for your approval.");
        statusView.setPadding(40, 24, 40, 24);
        new AlertDialog.Builder(this)
                .setTitle("Authorize Amiki action")
                .setMessage(summary.toString())
                .setView(statusView)
                .setNegativeButton("Cancel", (dialog, which) -> fail("Owner cancelled the action."))
                .setPositiveButton("Approve and verify", (dialog, which) -> beginElevation())
                .setOnCancelListener(dialog -> fail("Owner cancelled the action."))
                .show();
    }

    private void beginElevation() {
        if (inFlight || completed) return;
        inFlight = true;
        setStatus("Preparing a one-use UOSA biometric challenge…");
        new Thread(() -> {
            try {
                String ownerToken = OwnerSessionVault.readToken(this);
                if (ownerToken == null || ownerToken.length() < 32) {
                    throw new IllegalStateException("A current Core owner session is required.");
                }
                JSONObject challengeRequest = new JSONObject();
                challengeRequest.put("auth_level", "L2");
                challengeRequest.put("scope", "device.execute");
                challengeRequest.put("transaction_hash", request.transactionHash());
                JSONObject challenge = postJson(
                        ApiEndpoints.UOSA_ELEVATION_CHALLENGES,
                        challengeRequest.toString().getBytes(StandardCharsets.UTF_8), ownerToken);
                String challengeId = challenge.getString("challenge_id");
                String challengeMessage = challenge.getString("message");
                runOnUiThread(() -> authenticateAndAuthorize(challengeId, challengeMessage));
            } catch (Exception failure) {
                runOnUiThread(() -> fail(safeFailure(failure)));
            }
        }, "Amiki-UOSA-Action-Challenge").start();
    }

    private void authenticateAndAuthorize(String challengeId, String challengeMessage) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            PrivateKey privateKey = (PrivateKey) keyStore.getKey(OwnerSecurityActivity.KEY_ALIAS, null);
            if (privateKey == null) throw new IllegalStateException("The enrolled owner signing key is unavailable.");
            Signature signer = Signature.getInstance("SHA256withECDSA");
            signer.initSign(privateKey);
            Executor executor = ContextCompat.getMainExecutor(this);
            biometricPrompt = new BiometricPrompt(this, executor,
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationSucceeded(
                                @NonNull BiometricPrompt.AuthenticationResult result) {
                            Signature unlocked = result.getCryptoObject() == null
                                    ? null : result.getCryptoObject().getSignature();
                            if (unlocked == null) {
                                fail("Biometric verification did not return the signing operation.");
                                return;
                            }
                            try {
                                unlocked.update(challengeMessage.getBytes(StandardCharsets.UTF_8));
                                String signature = Base64.encodeToString(unlocked.sign(), Base64.NO_WRAP);
                                completeElevationAndAuthorize(challengeId, signature);
                            } catch (Exception failure) {
                                fail("The owner challenge could not be signed.");
                            }
                        }

                        @Override
                        public void onAuthenticationError(int code, @NonNull CharSequence message) {
                            fail("Biometric approval was cancelled or failed.");
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            setStatus("Biometric not recognized. Try again or cancel.");
                        }
                    });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Approve one Amiki action")
                    .setSubtitle(request.actionType.wireValue + " · " + request.packageName)
                    .setNegativeButtonText("Cancel")
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .build();
            biometricPrompt.authenticate(info, new BiometricPrompt.CryptoObject(signer));
        } catch (Exception failure) {
            fail("The enrolled biometric signing key could not be unlocked.");
        }
    }

    private void completeElevationAndAuthorize(String challengeId, String signature) {
        setStatus("Biometric accepted. Exchanging the one-use authorization…");
        new Thread(() -> {
            try {
                JSONObject proof = new JSONObject();
                proof.put("challenge_id", challengeId);
                proof.put("signature", signature);
                JSONObject grant = postJson(
                        ApiEndpoints.UOSA_ELEVATED_SESSIONS,
                        proof.toString().getBytes(StandardCharsets.UTF_8), null);
                String elevatedToken = grant.getString("access_token");
                if (!"L2".equals(grant.optString("auth_level"))
                        || !grant.optBoolean("single_use", false)
                        || !request.transactionHash().equals(grant.optString("transaction_hash"))) {
                    throw new IllegalStateException("UOSA returned a grant that did not match the exact action.");
                }
                byte[] exactActionBody = request.toCanonicalJson().getBytes(StandardCharsets.UTF_8);
                JSONObject receipt = postJson(ApiEndpoints.DEVICE_UI_ACTION_AUTHORIZE,
                        exactActionBody, elevatedToken);
                if (!request.matchesReceipt(receipt)) {
                    throw new IllegalStateException("Core authorization receipt did not match the requested action.");
                }
                runOnUiThread(() -> {
                    completed = true;
                    AccessibilityOperatorService.onUiActionAuthorizationGranted(actionId, receipt);
                    Toast.makeText(this, "One action authorized. Rechecking the foreground window…", Toast.LENGTH_SHORT).show();
                    finish();
                });
            } catch (Exception failure) {
                runOnUiThread(() -> fail(safeFailure(failure)));
            }
        }, "Amiki-UOSA-Action-Authorization").start();
    }

    private JSONObject postJson(String endpoint, byte[] body, String bearerToken) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(endpoint).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(7000);
            connection.setReadTimeout(12000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (bearerToken != null) connection.setRequestProperty("Authorization", "Bearer " + bearerToken);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String response = stream == null ? "" : readLimited(stream, 16384);
            if (status < 200 || status >= 300) {
                throw new java.io.IOException("Core rejected the owner action request (HTTP " + status + ").");
            }
            return new JSONObject(response);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > limit) throw new IllegalStateException("Core response exceeded the size limit.");
            output.write(buffer, 0, count);
        }
        return output.toString("UTF-8");
    }

    private void setStatus(String value) {
        runOnUiThread(() -> { if (statusView != null) statusView.setText(value); });
    }

    private void fail(String safeReason) {
        if (completed) return;
        completed = true;
        AccessibilityOperatorService.onUiActionAuthorizationFailed(actionId, safeReason);
        runOnUiThread(() -> {
            Toast.makeText(this, safeReason, Toast.LENGTH_LONG).show();
            finish();
        });
    }

    private static String safeFailure(Exception failure) {
        if (failure instanceof java.io.IOException && failure.getMessage() != null
                && failure.getMessage().contains("HTTP")) return failure.getMessage();
        return "Owner authorization failed. No UI action was executed.";
    }

    @Override
    public void onBackPressed() {
        fail("Owner cancelled the action.");
        super.onBackPressed();
    }
}
