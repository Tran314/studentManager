package com.utils;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/** Container health check using the bundled JDK; no curl dependency. */
public final class HealthProbe {
    private HealthProbe() {}

    public static void main(String[] args) {
        HttpURLConnection connection = null;
        int status = 1;
        try {
            connection = (HttpURLConnection) URI.create("http://127.0.0.1:8080/studentManagerSix/health")
                    .toURL()
                    .openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setInstanceFollowRedirects(false);
            if (connection.getResponseCode() == 200) {
                try (var input = connection.getInputStream()) {
                    if ("OK".equals(new String(input.readNBytes(3), StandardCharsets.US_ASCII))) status = 0;
                }
            }
        } catch (Exception ignored) {
            // Docker records the exit status. Never expose configuration in probe output.
        } finally {
            if (connection != null) connection.disconnect();
        }
        System.exit(status);
    }
}
