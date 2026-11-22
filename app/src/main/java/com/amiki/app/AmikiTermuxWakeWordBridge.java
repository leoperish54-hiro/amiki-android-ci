package com.amiki.app;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    interface ManualLaunchListener {
        void onCommandReady(String command, Runnable cancelSession);
    }

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
        ensureRunning(context, token, false, null);
    }

    static void ensureRunning(
            Context context, String token, boolean manual, ManualLaunchListener listener)
            throws IOException {
        try {
            if (isHealthy(token)) return;
        } catch (IOException bridgeNotRunning) {
            // Continue into the authenticated Termux launch and token handoff.
        }

        try (ServerSocket server = new ServerSocket()) {
            server.setReuseAddress(false);
            server.bind(new InetSocketAddress(
                    InetAddress.getByName("127.0.0.1"), 0), 4);
            handoffTokenToTermux(context, server, token, manual, listener);
        }
        awaitHealthy(token);
    }

    private static void handoffTokenToTermux(
            Context context, ServerSocket server, String token,
            boolean manual, ManualLaunchListener listener) throws IOException {
        String session = handoffSession();
        byte[] expectedChallenge =
                (session + "\n").getBytes(StandardCharsets.US_ASCII);
        AtomicBoolean delivered = new AtomicBoolean(false);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<IOException> failure = new AtomicReference<>();
        Thread acceptor = new Thread(() -> {
            while (!delivered.get() && !cancelled.get()) {
                try (Socket peer = server.accept()) {
                    peer.setSoTimeout(10_000);
                    byte[] supplied = new byte[expectedChallenge.length];
                    try {
                        InputStream input = peer.getInputStream();
                        int offset = 0;
                        while (offset < supplied.length) {
                            int count = input.read(supplied, offset, supplied.length - offset);
                            if (count < 0) throw new IOException("Incomplete handoff challenge.");
                            offset += count;
                        }
                    } catch (IOException malformedPeer) {
                        continue;
                    }
                    if (!MessageDigest.isEqual(expectedChallenge, supplied)) {
                        continue;
                    }
                    OutputStream output = peer.getOutputStream();
                    output.write((token + "\n").getBytes(StandardCharsets.US_ASCII));
                    output.flush();
                    delivered.set(true);
                    return;
                } catch (IOException error) {
                    if (!cancelled.get()) failure.set(error);
                    return;
                }
            }
        }, "AmikiTermuxWakeWordTokenHandoff");
        acceptor.setDaemon(true);
        acceptor.start();

        Runnable cancel = () -> {
            cancelled.set(true);
            try {
                server.close();
            } catch (IOException ignored) {
                // Closing the listener releases the blocking accept call.
            }
        };

        if (manual) {
            if (listener == null) {
                cancel.run();
                throw new IOException("Manual Termux launch instructions are unavailable.");
            }
            String command = "python \"$HOME/astra_core/scripts/amiki_wake_word_model_bridge.py\""
                    + " --token-handoff-port " + server.getLocalPort()
                    + " --token-handoff-session " + session;
            listener.onCommandReady(command, cancel);
        } else if (!TermuxBackendLauncher.startWakeWordModelBridge(
                context, server.getLocalPort(), session)) {
            cancel.run();
            joinQuietly(acceptor);
            throw new IOException(
                    "Could not start the Termux model bridge. Check that Termux is installed, "
                    + "external command access is enabled, and Amiki has RUN_COMMAND permission.");
        }

        try {
            if (manual) {
                // Manual handoff does not expire. It remains available until Termux
                // completes the one-use challenge or the owner explicitly cancels it.
                acceptor.join();
            } else {
                acceptor.join(TOKEN_HANDOFF_TIMEOUT_MS);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            cancel.run();
            throw new IOException("Termux token handoff was interrupted.", interrupted);
        }
        if (!delivered.get()) {
            if (cancelled.get()) {
                throw new IOException("Manual Termux handoff was cancelled.");
            }
            if (acceptor.isAlive()) {
                cancel.run();
                throw new IOException("Termux did not connect to Amiki's local handoff server.");
            }
            IOException cause = failure.get();
            throw new IOException(
                    "Termux could not complete the authenticated local token handoff.", cause);
        }
    }

    private static String handoffSession() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder value = new StringBuilder(64);
        for (byte item : bytes) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        return value.toString();
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
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
