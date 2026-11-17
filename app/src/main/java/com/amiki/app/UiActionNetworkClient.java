package com.amiki.app;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Reports metadata-only execution evidence to Core using the existing L1 session. */
final class UiActionNetworkClient {
    interface Callback {
        void onComplete(boolean recorded, String safeDetail);
    }

    private UiActionNetworkClient() { }

    static void recordOutcome(Context context, UiActionDispatcher.Request request,
                              String resultCode, Callback callback) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String token = OwnerSessionVault.readToken(context);
                if (token == null || token.length() < 32) {
                    throw new IllegalStateException("The Core owner session is unavailable.");
                }
                JSONObject body = new JSONObject();
                body.put("action_id", request.actionId);
                body.put("action_type", request.actionType.wireValue);
                body.put("package_name", request.packageName);
                if (request.payloadSha256 != null) body.put("payload_sha256", request.payloadSha256);
                body.put("postcondition_sha256", request.postconditionSha256);
                body.put("postcondition_type", request.postconditionType);
                body.put("result_code", resultCode);
                body.put("snapshot_age_ms", request.snapshotAgeMs);
                body.put("snapshot_fingerprint", request.snapshotFingerprint);
                body.put("target_fingerprint", request.targetFingerprint);
                body.put("task_id", request.taskId);
                body.put("window_id", request.windowId);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection = ApiConnection.openWithBearer(ApiEndpoints.DEVICE_UI_ACTION_RESULT, token);
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(12000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Accept", "application/json");
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream output = connection.getOutputStream()) { output.write(bytes); }
                int status = connection.getResponseCode();
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream() : connection.getErrorStream();
                String response = stream == null ? "" : readLimited(stream, 8192);
                if (status < 200 || status >= 300) {
                    throw new IllegalStateException("Core could not append the action outcome receipt (HTTP "
                            + status + ").");
                }
                JSONObject receipt = new JSONObject(response);
                if (!"recorded".equals(receipt.optString("status"))
                        || !request.actionId.equals(receipt.optString("action_id"))
                        || !resultCode.equals(receipt.optString("result_code"))) {
                    throw new IllegalStateException("Core returned an invalid action outcome receipt.");
                }
                if (callback != null) callback.onComplete(true, "Outcome receipt recorded in Cipher.");
            } catch (Exception failure) {
                if (callback != null) callback.onComplete(false,
                        failure.getMessage() == null ? "Outcome receipt could not be recorded." : failure.getMessage());
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "Amiki-UI-Action-Audit").start();
    }

    private static String readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IllegalStateException("Core response exceeded the size limit.");
            output.write(buffer, 0, read);
        }
        return output.toString("UTF-8");
    }
}
