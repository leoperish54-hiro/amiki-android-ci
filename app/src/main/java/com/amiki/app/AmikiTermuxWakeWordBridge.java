package com.amiki.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Credentials;
import android.net.LocalServerSocket;
import android.net.LocalSocket;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Authenticated loopback client for Termux's model-only provisioning service. */
final class AmikiTermuxWakeWordBridge {
    private static final String PREFS = "amiki_termux_wake_word_bridge";
    private static final String TOKEN_KEY = "bridge_token";
    private static final String BASE_URL = "http://127.0.0.1:8766";
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int READ_TIMEOUT_MS = 2500;
    private static final long TOKEN_HANDOFF_TIMEOUT_MS = 30_000L;
    private static final long STARTUP_TIMEOUT_MS = 1_320_000L;

    private AmikiTermuxWakeWordBridge() { }

    static String token(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String existing = preferences.getString(TOKEN_KEY, null);
        if (existing != null && existing.length() == 64) return existing;

        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        String generated = value.toString();
        if (!preferences.edit().putString(TOKEN_KEY, generated).commit()) {
            throw new IllegalStateException("Could not persist the local Termux bridge token.");
        }
        return generated;
    }

    static void ensureRunning(Context context, String token) throws IOException {
        // A refused connection is the expected state before the bridge is launched.
        // Only return early when the authenticated health probe succeeds.
        try {
            if (isHealthy(token)) return;
        } catch (IOException bridgeNotRunning) {
            // Continue into the authenticated Termux launch and token handoff.
        }

        String socketName = bootstrapSocketName();
        try (LocalServerSocket server = new LocalServerSocket(socketName)) {
            handoffTokenToTermux(context, server, socketName, token);
        }
        awaitHealthy(token);
    }

    private static void handoffTokenToTermux(
            Context context, LocalServerSocket server, String socketName, String token)
            throws IOException {
        final int expectedUid;
        try {
            expectedUid = context.getPackageManager()
                    .getApplicationInfo(TermuxBackendLauncher.PACKAGE_NAME, 0).uid;
        } catch (PackageManager.NameNotFoundException missingTermux) {
            throw new IOException("Termux is not installed.", missingTermux);
        }

        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread acceptor = new Thread(() -> {
            int rejectedPeers = 0;
            while (!delivered.get() && rejectedPeers < 16) {
                try (LocalSocket peer = server.accept()) {
                    Credentials credentials = peer.getPeerCredentials();
                    if (credentials == null || credentials.getUid() != expectedUid) {
                        rejectedPeers++;
                        continue;
                    }
                    OutputStream output = peer.getOutputStream();
                    output.write((token + "\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    delivered.set(true);
                    return;
                } catch (IOException error) {
                    failure.set(error);
                    return;
                }
            }
            if (!delivered.get()) {
                failure.set(new IOException(
                        "Too many non-Termux processes attempted the token handoff."));
            }
        }, "AmikiTermuxWakeWordTokenHandoff");
        acceptor.setDaemon(true);
        acceptor.start();

        if (!TermuxBackendLauncher.startWakeWordModelBridge(context, socketName)) {
            closeQuietly(server);
            joinQuietly(acceptor);
            throw new IOException(
                    "Could not start the Termux model bridge. Check that Termux is installed, "
                    + "external command access is enabled, and Amiki has RUN_COMMAND permission.");
        }

        try {
            acceptor.join(TOKEN_HANDOFF_TIMEOUT_MS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            closeQuietly(server);
            throw new IOException("Termux token handoff was interrupted.", interrupted);
        }
        if (acceptor.isAlive()) {
            closeQuietly(server);
            throw new IOException("Termux did not connect to Amiki's local bootstrap socket.");
        }
        if (!delivered.get()) {
            IOException cause = failure.get();
            throw new IOException(
                    "Termux could not complete the authenticated local token handoff.", cause);
        }
    }

    private static void awaitHealthy(String token) throws IOException {
        long deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS;
        IOException lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            if (Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
                throw new IOException("Termux model bridge startup was interrupted.");
            }
            try {
                if (isHealthy(token)) return;
            } catch (IOException failure) {
                lastFailure = failure;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Termux model bridge startup was interrupted.", interrupted);
            }
        }
        throw new IOException(
                "Termux did not finish verifying the wake-word model within 22 minutes.",
                lastFailure);
    }

    private static String bootstrapSocketName() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder("amiki-wake-word-");
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static void closeQuietly(LocalServerSocket server) {
        try {
            server.close();
        } catch (IOException ignored) {
            // The acceptor is being stopped; no further action is required.
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static HttpURLConnection openAssetConnection(String token, String assetName)
            throws IOException {
        URL url = new URL(BASE_URL + "/v1/assets/" + assetName);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(90_000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Authorization", "Bearer " + token);
        connection.setRequestProperty("Accept", "application/octet-stream");
        return connection;
    }

    static void stop(String token) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE_URL + "/v1/shutdown").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setFixedLengthStreamingMode(0);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.getResponseCode();
        } catch (IOException ignored) {
            // The bridge may already have exited or failed before becoming ready.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static boolean isHealthy(String token) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(BASE_URL + "/v1/health").openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            return connection.getResponseCode() == HttpURLConnection.HTTP_OK;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
