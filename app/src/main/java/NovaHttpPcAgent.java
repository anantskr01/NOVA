package com.aircontrol;

import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Real authenticated HTTP client for NOVA's PC companion. */
public final class NovaHttpPcAgent implements NovaPcAgent {
    private final String baseUrl;
    private final NovaPcCredentials credentials;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public NovaHttpPcAgent(android.content.Context context, String baseUrl) {
        this(context, baseUrl, 5_000, 30_000);
    }

    public NovaHttpPcAgent(android.content.Context context, String baseUrl, int connectTimeoutMs, int readTimeoutMs) {
        this.baseUrl = normalize(baseUrl);
        this.credentials = new NovaPcCredentials(context);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
    }

    @Override
    public boolean isConnected() {
        try {
            JSONObject response = request("GET", "/v1/health", "", false);
            return response.optBoolean("ok", false) && response.optInt("protocol", 0) == 1;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public NovaToolResult execute(NovaToolInput input) {
        if (input == null || input.toolType.isEmpty()) return NovaToolResult.failure("pc", "invalid_input", "Missing PC tool input", false);
        if (credentials.getSecret().isEmpty()) return NovaToolResult.failure(input.toolType, "pc_not_paired", "PC companion credentials are not configured", false);
        try {
            JSONObject request = new JSONObject();
            request.put("id", UUID.randomUUID().toString());
            request.put("tool", input.toolType);
            request.put("args", input.arguments);
            JSONObject response = request("POST", "/v1/execute", request.toString(), true);
            boolean ok = response.optBoolean("ok", false);
            if (ok) return NovaToolResult.success(input.toolType, response.toString(), response.optBoolean("verified", false));
            return NovaToolResult.failure(input.toolType, response.optString("error", "pc_execution_failed"), response.toString(), isRetryable(response));
        } catch (Exception e) {
            return NovaToolResult.failure(input.toolType, "pc_transport_error", e.getMessage() == null ? "PC companion unavailable" : e.getMessage(), true);
        }
    }

    @Override
    public String observe() {
        try {
            return request("GET", "/v1/observe", "", true).toString();
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"pc_transport_error\"}";
        }
    }

    @Override
    public void disconnect() {
        // The companion is stateless between requests; clearing credentials is an explicit caller action.
    }

    private JSONObject request(String method, String path, String body, boolean authenticated) throws Exception {
        String secret = credentials.getSecret();
        long timestamp = System.currentTimeMillis() / 1000L;
        String nonce = UUID.randomUUID().toString();
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeoutMs);
        connection.setReadTimeout(readTimeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        if ("POST".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        if (authenticated) {
            connection.setRequestProperty("X-NOVA-Timestamp", Long.toString(timestamp));
            connection.setRequestProperty("X-NOVA-Nonce", nonce);
            connection.setRequestProperty("X-NOVA-Signature", hmac(secret, timestamp + "\n" + nonce + "\n" + body));
        }
        if ("POST".equals(method)) connection.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
        int code = connection.getResponseCode();
        InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String text = read(stream);
        connection.disconnect();
        if (text.isEmpty()) text = "{\"ok\":false,\"error\":\"empty_response\"}";
        return new JSONObject(text);
    }

    private static boolean isRetryable(JSONObject response) {
        return response.optBoolean("retryable", false) || "timeout".equals(response.optString("error"));
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 128_000) break;
                out.append(line);
            }
            return out.toString();
        }
    }

    private static String hmac(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String normalize(String url) {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("PC companion URL is required");
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
