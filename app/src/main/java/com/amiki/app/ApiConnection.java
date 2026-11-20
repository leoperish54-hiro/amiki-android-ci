package com.amiki.app;

import android.content.Context;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;

/** Attaches the capability-appropriate UOSA session to local Core API requests. */
final class ApiConnection {
    private ApiConnection() {}

    static HttpURLConnection open(Context context, String endpoint) throws IOException {
        return open(context, new URL(endpoint));
    }

    static HttpURLConnection open(Context context, URL endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
        if (!isCoreApiEndpoint(endpoint)) return connection;
        try {
            String token = isPrivilegedApiPath(endpoint.getPath())
                    ? OwnerSessionProvider.getPrivilegedToken(context)
                    : OwnerSessionProvider.getGeneralToken(context);
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            return connection;
        } catch (Exception failure) {
            connection.disconnect();
            String profile = isPrivilegedApiPath(endpoint.getPath())
                    ? "privileged Cipher/ALE/Cloud" : "general";
            throw new IOException(
                    "The saved UOSA " + profile + " session could not be unlocked or renewed. Open Owner Security.",
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

    static boolean isPrivilegedApiPath(String path) {
        return path != null && (
                path.startsWith("/api/v1/cipher/")
                || path.startsWith("/api/v1/learning/")
                || path.startsWith("/api/v1/cloud/")
        );
    }
}
