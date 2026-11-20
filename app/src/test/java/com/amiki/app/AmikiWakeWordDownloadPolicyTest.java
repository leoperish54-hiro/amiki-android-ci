package com.amiki.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import org.junit.Test;

public final class AmikiWakeWordDownloadPolicyTest {
    @Test
    public void retriesOnlyTransientHttpStatuses() {
        assertTrue(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(408));
        assertTrue(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(429));
        assertTrue(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(503));
        assertTrue(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(599));
        assertFalse(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(400));
        assertFalse(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(401));
        assertFalse(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(403));
        assertFalse(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(404));
        assertFalse(AmikiWakeWordDownloadPolicy.isRetryableHttpStatus(416));
    }

    @Test
    public void retriesTransientSocketFailures() {
        assertTrue(AmikiWakeWordDownloadPolicy.isRetryableException(
                new SocketTimeoutException("read timed out")));
        assertTrue(AmikiWakeWordDownloadPolicy.isRetryableException(
                new UnknownHostException("temporary DNS failure")));
    }

    @Test
    public void doesNotRetryStorageFailures() {
        assertFalse(AmikiWakeWordDownloadPolicy.isRetryableException(
                new IOException("No space left on device")));
        assertTrue(AmikiWakeWordDownloadPolicy.isStorageFailure(
                new IOException("ENOSPC")));
    }

    @Test
    public void retryBackoffIsBounded() {
        assertTrue(AmikiWakeWordDownloadPolicy.retryDelayMillis(1) <= 1000L);
        assertTrue(AmikiWakeWordDownloadPolicy.retryDelayMillis(2) <= 2000L);
        assertTrue(AmikiWakeWordDownloadPolicy.retryDelayMillis(100) <= 2000L);
    }

    @Test
    public void diagnosticNamesFinalHostAndByteCountWithoutUrlSecrets() {
        String message = AmikiWakeWordDownloadPolicy.userMessage(
                "encoder.onnx", 0L, 4000908L, -1,
                "cas-bridge.xethub.hf.co",
                new UnknownHostException("host not found"), 3);
        assertTrue(message.contains("cas-bridge.xethub.hf.co"));
        assertTrue(message.contains("0 of 4000908 bytes"));
        assertFalse(message.contains("token="));
        assertFalse(message.contains("https://"));
    }
}
