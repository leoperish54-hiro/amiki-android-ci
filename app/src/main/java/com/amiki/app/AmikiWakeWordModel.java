package com.amiki.app;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Downloads and verifies the small, quantized sherpa-onnx English KWS model.
 *
 * Model files are kept in app-private storage. Every file is pinned by size
 * and SHA-256; installation is staged and swapped only after all five files
 * pass verification. The model is Apache-2.0 licensed and the runtime phrase
 * is supplied directly to KeywordSpotter at startup.
 */
final class AmikiWakeWordModel {
    private static final String MODEL_DIRECTORY = "amiki-hey-amiki-kws";
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final String KEYWORDS_FILE = "keywords.txt";
    private static final long KEYWORDS_SIZE = 32L;
    private static final String KEYWORDS_SHA256 =
            "b18718cab74c7e3882cbae7dca87b35e4c09fef99ad1e6c7aa91677ee15b60e6";
    private static final String MODEL_BASE_URL =
            "https://huggingface.co/mobilebytesensei/"
            + "sherpa-onnx-kws-zipformer-gigaspeech-3.3M/resolve/main/";

    private static final Asset[] ASSETS = new Asset[]{
            new Asset(
                    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    4000908L,
                    "7d5a25fbc14b423074fe0c58e1be388ccf3f1a815c649980232a0c47f81875e4"),
            new Asset(
                    "decoder-epoch-12-avg-2-chunk-16-left-64.onnx",
                    1063189L,
                    "f61ebd3eed3773a44d088d53dfae92dbb6aec4839f4dcaee2d402414741663a3"),
            new Asset(
                    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
                    163380L,
                    "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c"),
            new Asset(
                    "tokens.txt",
                    5006L,
                    "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53"),
            new Asset(
                    "bpe.model",
                    244837L,
                    "c8a2a0129c4ab8e463164c142f82d25649661b122c8cd0b7aab5c9e80b90ad24")
    };

    interface ProgressListener {
        void onProgress(int percentage, String detail);
    }

    private AmikiWakeWordModel() { }

    static File modelDirectory(Context context) {
        return new File(context.getFilesDir(), MODEL_DIRECTORY);
    }

    static boolean isInstalled(Context context) {
        if (context == null) return false;
        File directory = modelDirectory(context);
        if (!directory.isDirectory()) return false;
        for (Asset asset : ASSETS) {
            File file = new File(directory, asset.name);
            try {
                if (!file.isFile() || file.length() != asset.size
                        || !asset.sha256.equals(sha256(file))) {
                    return false;
                }
            } catch (IOException failure) {
                return false;
            }
        }
        File keywords = new File(directory, KEYWORDS_FILE);
        try {
            return keywords.isFile() && keywords.length() == KEYWORDS_SIZE
                    && KEYWORDS_SHA256.equals(sha256(keywords));
        } catch (IOException failure) {
            return false;
        }
    }

    static void install(Context context, ProgressListener listener) throws IOException {
        if (context == null) throw new IllegalArgumentException("A context is required.");
        if (isInstalled(context)) {
            report(listener, 100, "Local wake-word model is already verified.");
            return;
        }

        File parent = context.getFilesDir();
        File staging = new File(parent, MODEL_DIRECTORY + ".staging");
        File installed = modelDirectory(context);
        File backup = new File(parent, MODEL_DIRECTORY + ".previous");

        deleteRecursively(staging);
        deleteRecursively(backup);
        if (!staging.mkdirs() && !staging.isDirectory()) {
            throw new IOException("Could not create private model staging storage.");
        }

        String bridgeToken = AmikiTermuxWakeWordBridge.token(context);
        try {
            report(listener, 0, "Starting Termux and verifying its local wake-word model cache…");
            AmikiTermuxWakeWordBridge.ensureRunning(context, bridgeToken);
            for (int index = 0; index < ASSETS.length; index++) {
                Asset asset = ASSETS[index];
                File destination = new File(staging, asset.name);
                report(listener, index * 100 / ASSETS.length,
                        "Downloading local wake-word model (" + (index + 1)
                                + " of " + ASSETS.length + ")…");
                downloadAndVerify(asset, destination, index, listener, bridgeToken);
            }

            report(listener, 98, "Installing the verified Hey Amiki keyword phrase…");
            copyVerifiedKeywordAsset(context, staging);

            boolean hadPrevious = installed.exists();
            if (hadPrevious && !installed.renameTo(backup)) {
                throw new IOException("Could not safely stage the previous wake-word model.");
            }
            if (!staging.renameTo(installed)) {
                if (hadPrevious) backup.renameTo(installed);
                throw new IOException("Could not publish the verified wake-word model.");
            }
            if (!isInstalled(context)) {
                deleteRecursively(installed);
                if (hadPrevious) backup.renameTo(installed);
                throw new IOException("Installed wake-word model failed final integrity checks.");
            }
            deleteRecursively(backup);
            report(listener, 100, "Hey Amiki's local wake-word model is installed and verified.");
        } catch (IOException | RuntimeException failure) {
            deleteRecursively(staging);
            if (!installed.exists() && backup.exists()) backup.renameTo(installed);
            throw failure;
        } finally {
            AmikiTermuxWakeWordBridge.stop(bridgeToken);
        }
    }

    private static void downloadAndVerify(
            Asset asset, File destination, int index, ProgressListener listener, String bridgeToken) throws IOException {
        DownloadAttemptException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_DOWNLOAD_ATTEMPTS; attempt++) {
            try {
                downloadAndVerifyOnce(asset, destination, index, listener, bridgeToken);
                return;
            } catch (DownloadAttemptException failure) {
                lastFailure = failure;
                Throwable cause = failure.getCause() == null ? failure : failure.getCause();
                boolean retryable = AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(failure.httpStatus)
                        || AmikiWakeWordDownloadPolicy.isRetryableException(cause);
                android.util.Log.w("AmikiWakeWord",
                        "Model asset transfer failed: asset=" + asset.name
                                + ", attempt=" + attempt + "/" + MAX_DOWNLOAD_ATTEMPTS
                                + ", host=" + failure.host
                                + ", http=" + failure.httpStatus
                                + ", bytes=" + failure.receivedBytes + "/" + asset.size
                                + ", cause=" + cause.getClass().getSimpleName(),
                        cause);

                if (!retryable || attempt == MAX_DOWNLOAD_ATTEMPTS) {
                    throw new IOException(AmikiWakeWordDownloadPolicy.userMessage(
                            asset.name, failure.receivedBytes, asset.size, failure.httpStatus,
                            failure.host, cause, attempt), failure);
                }

                report(listener, index * 100 / ASSETS.length,
                        "Download interrupted (" + failure.receivedBytes + " of " + asset.size
                                + " bytes). Retrying (" + (attempt + 1) + " of "
                                + MAX_DOWNLOAD_ATTEMPTS + ")…");
                try {
                    Thread.sleep(AmikiWakeWordDownloadPolicy.retryDelayMillis(attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Wake-word model download was interrupted before retry.", interrupted);
                }
            }
        }
        throw new IOException("Wake-word model download failed.", lastFailure);
    }

    private static void downloadAndVerifyOnce(
            Asset asset, File destination, int index, ProgressListener listener, String bridgeToken)
            throws DownloadAttemptException {
        File temporary = new File(destination.getParentFile(), asset.name + ".part");
        HttpURLConnection connection = null;
        long received = 0L;
        int status = -1;
        String host = "127.0.0.1";
        try {
            connection = AmikiTermuxWakeWordBridge.openAssetConnection(bridgeToken, asset.name);
            status = connection.getResponseCode();
            if (connection.getURL() != null && connection.getURL().getHost() != null) {
                host = connection.getURL().getHost();
            }
            // The authenticated Termux bridge serves only its independently verified
            // cache. Amiki still performs its own exact size and SHA-256 verification.
            if (status != HttpURLConnection.HTTP_OK) {
                throw new IOException("Unexpected HTTP status " + status + ".");
            }
            long contentLength = connection.getContentLengthLong();
            if (AmikiWakeWordDownloadPolicy.isContentLengthMismatch(contentLength, asset.size)) {
                // CDN/proxy metadata is advisory only. Do not reject before reading:
                // the streamed byte count and pinned SHA-256 below are authoritative.
                android.util.Log.w("AmikiWakeWord",
                        "Model host Content-Length differs from pinned size: asset="
                                + asset.name + ", declared=" + contentLength
                                + ", pinned=" + asset.size + ", host=" + host);
            }

            MessageDigest digest = newSha256();
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(
                         new FileOutputStream(temporary))) {
                byte[] buffer = new byte[16384];
                int count;
                while (received < asset.size) {
                    // Never write bytes beyond the independently pinned model size.
                    // Some CDN responses append transport data; only the exact
                    // expected prefix is eligible for SHA-256 verification.
                    int requested = AmikiWakeWordDownloadPolicy.boundedReadLength(
                            buffer.length, asset.size, received);
                    count = input.read(buffer, 0, requested);
                    if (count == -1) break;
                    received += count;
                    digest.update(buffer, 0, count);
                    output.write(buffer, 0, count);
                    int fileProgress = (int) Math.min(99L, received * 100L / asset.size);
                    int overall = (index * 100 + fileProgress) / ASSETS.length;
                    report(listener, overall, "Downloading " + asset.name + "…");
                }

                // Stop exactly at the pinned size. Do not perform an extra
                // network read here: some CDN streams can fail while probing
                // beyond the declared body even after all verified bytes arrived.
                // Closing the stream is sufficient; only the bounded prefix is
                // written and its SHA-256 remains mandatory below.
            }

            if (received < asset.size) {
                throw new java.io.EOFException("The model transfer ended before all bytes arrived.");
            }
            String actual = hex(digest.digest());
            if (!asset.sha256.equals(actual)) {
                throw new IOException("The downloaded model did not match its pinned SHA-256.");
            }
            if (!temporary.renameTo(destination)) {
                throw new IOException("Could not store the verified model file " + asset.name + ".");
            }
        } catch (IOException failure) {
            throw new DownloadAttemptException(received, status, host, failure);
        } finally {
            if (connection != null) connection.disconnect();
            if (temporary.exists()) temporary.delete();
        }
    }

    private static final class DownloadAttemptException extends IOException {
        final long receivedBytes;
        final int httpStatus;
        final String host;

        DownloadAttemptException(long receivedBytes, int httpStatus, String host, IOException cause) {
            super("Wake-word model transfer attempt failed.", cause);
            this.receivedBytes = receivedBytes;
            this.httpStatus = httpStatus;
            this.host = host;
        }
    }

    private static void copyVerifiedKeywordAsset(Context context, File directory)
            throws IOException {
        File destination = new File(directory, KEYWORDS_FILE);
        File temporary = new File(directory, KEYWORDS_FILE + ".part");
        MessageDigest digest = newSha256();
        long copied = 0L;
        try (InputStream input = context.getAssets().open(KEYWORDS_FILE);
             BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(temporary))) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                copied += count;
                if (copied > KEYWORDS_SIZE) {
                    throw new IOException("The bundled wake-word phrase exceeded its pinned size.");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        } catch (IOException failure) {
            temporary.delete();
            throw failure;
        }

        String actual = hex(digest.digest());
        if (copied != KEYWORDS_SIZE || !KEYWORDS_SHA256.equals(actual)) {
            temporary.delete();
            throw new IOException("The bundled Hey Amiki keyword phrase failed integrity verification.");
        }
        if (!temporary.renameTo(destination)) {
            temporary.delete();
            throw new IOException("Could not install the verified Hey Amiki keyword phrase.");
        }
    }

    private static MessageDigest newSha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 is unavailable on this Android device.", impossible);
        }
    }

    private static String sha256(File file) throws IOException {
        MessageDigest digest = newSha256();
        try (InputStream input = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte item : bytes) {
            value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
        }
        return value.toString();
    }

    private static void report(ProgressListener listener, int progress, String detail) {
        if (listener != null) {
            listener.onProgress(Math.max(0, Math.min(100, progress)), detail);
        }
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        file.delete();
    }

    private static final class Asset {
        final String name;
        final long size;
        final String sha256;

        Asset(String name, long size, String sha256) {
            this.name = name;
            this.size = size;
            this.sha256 = sha256;
        }
    }
}
