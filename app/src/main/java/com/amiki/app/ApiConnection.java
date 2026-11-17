package com.amiki.app;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Adds the current short-lived UOSA session to local Core API requests. */
final class ApiConnection {
    private ApiConnection() {}

    static HttpURLConnection open(Context context, String endpoint) throws IOException {
        return open(context, new URL(endpoint));
    }

    static HttpURLConnection open(Context context, URL endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        if (!isCoreApiEndpoint(endpoint)) return connection;
        try {
            String token = OwnerSessionVault.readToken(context);
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            return connection;
        } catch (Exception failure) {
            connection.disconnect();
            throw new IOException(
                    "The saved UOSA session could not be unlocked. Create a fresh owner session in Privacy & Security.",
                    failure
            );
        }
    }

    static HttpURLConnection openWithBearer(String endpoint, String bearerToken) throws IOException {
        URL url = new URL(endpoint);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        if (isCoreApiEndpoint(url) && bearerToken != null && !bearerToken.trim().isEmpty()) {
            connection.setRequestProperty("Authorization", "Bearer " + bearerToken.trim());
        }
        return connection;
    }

    static boolean isCoreApiEndpoint(URL endpoint) {
        try {
            URL base = new URL(ApiEndpoints.BASE_URL);
            return "http".equalsIgnoreCase(endpoint.getProtocol())
                    && base.getHost().equalsIgnoreCase(endpoint.getHost())
                    && base.getPort() == endpoint.getPort()
                    && endpoint.getPath().startsWith("/api/v1/");
        } catch (Exception ignored) {
            return false;
        }
    }
}
