package com.amiki.app;

import java.io.EOFException;
import java.io.FileNotFoundException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import javax.net.ssl.SSLException;

/**
 * Bounded retry and user-safe diagnostics for the small, pinned wake-word assets.
 *
 * Adapted for Amiki from the download-failure taxonomy used by NIDO's MIT-licensed
 * ModelManager. Amiki keeps its own Java implementation and only retries failures
 * that can plausibly recover without weakening size or SHA-256 verification.
 */
final class AmikiWakeWordDownloadPolicy {
    private AmikiWakeWordDownloadPolicy() { }

    static boolean isRetryableHttpStatus(int status) {
        return status == 408 || status == 429 || (status >= 500 && status <= 599);
    }

    static boolean isRetryableException(Throwable failure) {
        if (isStorageFailure(failure)) return false;
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof FileNotFoundException) return false;
            if (current instanceof SocketTimeoutException
                    || current instanceof UnknownHostException
                    || current instanceof ConnectException
                    || current instanceof SocketException
                    || current instanceof EOFException) {
                return true;
            }
        }
        return false;
    }

    static boolean isStorageFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message == null) continue;
            String normalized = message.toLowerCase(java.util.Locale.ROOT);
            if (normalized.contains("no space left")
                    || normalized.contains("enospc")
                    || normalized.contains("disk full")
                    || normalized.contains("quota exceeded")
                    || normalized.contains("not enough space")) {
                return true;
            }
        }
        return false;
    }

    static long retryDelayMillis(int failedAttempt) {
        return failedAttempt <= 1 ? 750L : 1500L;
    }

    static String userMessage(
            String assetName,
            long receivedBytes,
            long expectedBytes,
            int httpStatus,
            String host,
            Throwable cause,
            int attempts) {
        String safeHost = host == null || host.trim().isEmpty() ? "the model host" : host;
        String description;

        if (isStorageFailure(cause)) {
            description = "Phone storage ran out while saving the wake-word model. Free up storage and retry.";
        } else if (httpStatus == 401 || httpStatus == 403) {
            description = "The model server denied the download (HTTP " + httpStatus
                    + ", " + safeHost + "). Check whether this network allows Hugging Face downloads.";
        } else if (httpStatus == 404) {
            description = "The requested model file was not found on " + safeHost
                    + ". The pinned model source may have changed.";
        } else if (httpStatus == 429) {
            description = "The model server is rate-limiting downloads from " + safeHost + ". Try again later.";
        } else if (httpStatus >= 500) {
            description = "The model server returned HTTP " + httpStatus + " from " + safeHost
                    + ". This may be temporary; retry later.";
        } else if (cause instanceof UnknownHostException) {
            description = "The phone could not resolve " + safeHost
                    + ". Check your connection or DNS settings.";
        } else if (cause instanceof SocketTimeoutException) {
            description = "The connection to " + safeHost + " timed out.";
        } else if (cause instanceof SSLException) {
            description = "The secure connection to " + safeHost + " failed. Check the phone's date, time, and network.";
        } else if (isStorageFailure(cause)) {
            description = "Phone storage is insufficient for this download.";
        } else {
            description = "The wake-word model download failed for " + assetName + " via " + safeHost + ".";
        }

        return description + " Received " + receivedBytes + " of " + expectedBytes
                + " bytes after " + attempts + " attempt(s).";
    }
}
