package com.amiki.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Bridges background agent requests to an explicit owner biometric-approval Activity. */
final class UosaAgentAuthorization {
    private static final ConcurrentHashMap<String, Pending> PENDING = new ConcurrentHashMap<>();

    private UosaAgentAuthorization() {}

    static String obtainTokenBlocking(Activity activity, JSONObject request) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Agent authorization must be requested from a background worker.");
        }
        String requestId = UUID.randomUUID().toString();
        Pending pending = new Pending();
        PENDING.put(requestId, pending);
        String serialized = request.toString();
        new Handler(Looper.getMainLooper()).post(() -> {
            if (activity.isFinishing()
                    || (android.os.Build.VERSION.SDK_INT >= 17 && activity.isDestroyed())) {
                complete(requestId, null, "The Amiki screen closed before owner approval.");
                return;
            }
            try {
                Intent intent = new Intent(activity, UosaAgentApprovalActivity.class);
                intent.putExtra(UosaAgentApprovalActivity.EXTRA_REQUEST_ID, requestId);
                intent.putExtra(UosaAgentApprovalActivity.EXTRA_REQUEST_JSON, serialized);
                activity.startActivity(intent);
            } catch (Exception failure) {
                complete(requestId, null, "Could not open the owner approval screen. The agent request was not sent.");
            }
        });
        boolean completed = pending.latch.await(150, TimeUnit.SECONDS);
        if (!completed) {
            PENDING.remove(requestId);
            throw new java.io.IOException("Owner biometric approval timed out. The agent request was not sent.");
        }
        if (pending.error != null) throw new java.io.IOException(pending.error);
        if (pending.token == null || pending.token.length() < 32) {
            throw new java.io.IOException("Owner approval did not produce a valid one-use agent authorization.");
        }
        return pending.token;
    }

    static void complete(String requestId, String token, String error) {
        Pending pending = PENDING.remove(requestId);
        if (pending == null) return;
        pending.token = token;
        pending.error = error;
        pending.latch.countDown();
    }

    private static final class Pending {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile String token;
        volatile String error;
    }
}
