package com.aircontrol;

import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;

/** Authenticated Android client for the trusted NOVA PC companion. */
public final class NovaPcHttpClient implements NovaPcAgent {
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 20_000;
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private final String baseUrl;
    private final String secret;
    private volatile boolean connected;

    public NovaPcHttpClient(String baseUrl, String secret) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.secret = secret == null ? "" : secret;
    }

    @Override public boolean isConnected() {
        if (baseUrl.isEmpty() || secret.length() < 32) {
            connected = false;
            return false;
        }
        try {
            JSONObject response = request("/v1/health", "GET", "");
            connected = response.optBoolean("ok", false)
                    && response.optInt("protocol", 0) == 1
                    && response.optBoolean("verified", false);
            return connected;
        } catch (Exception e) {
            connected = false;
            return false;
        }
    }

    @Override public NovaToolResult execute(NovaToolInput input) {
        if (input == null) return NovaToolResult.failure("", "invalid_input", "Invalid PC tool input", false);
        if (baseUrl.isEmpty() || secret.length() < 32) {
            connected = false;
            return NovaToolResult.failure(input.toolType, "pc_not_configured", "PC companion is not configured", false);
        }
        try {
            JSONObject request = new JSONObject()
                    .put("id", UUID.randomUUID().toString())
                    .put("tool", input.toolType)
                    .put("args", input.arguments);
            JSONObject response = request("/v1/execute", "POST", request.toString());
            boolean ok = response.optBoolean("ok", false);
            connected = ok;
            if (ok) {
                return NovaToolResult.success(input.toolType, response.toString(), response.optBoolean("verified", false));
            }
            String error = response.optString("error", "pc_tool_failed");
            return NovaToolResult.failure(input.toolType, error, response.toString(), response.optBoolean("retryable", false));
        } catch (Exception e) {
            connected = false;
            return NovaToolResult.failure(input.toolType, "pc_request_failed", compactError(e), true);
        }
    }

    @Override public String observe() {
        if (baseUrl.isEmpty() || secret.length() < 32) {
            connected = false;
            return "pc_not_configured";
        }
        try {
            JSONObject response = request("/v1/observe", "GET", "");
            connected = response.optBoolean("ok", false) && response.optBoolean("verified", false);
            return response.toString();
        } catch (Exception e) {
            connected = false;
            return "pc_observe_failed:" + compactError(e);
        }
    }

    @Override public void disconnect() { connected = false; }

    private JSONObject request(String path, String method, String body) throws Exception {
        URI uri = URI.create(baseUrl + path);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            long timestamp = System.currentTimeMillis() / 1000L;
            String nonce = UUID.randomUUID().toString();
            String signature = hmac(timestamp + "\n" + nonce + "\n" + body);
            connection.setRequestProperty("X-NOVA-Timestamp", Long.toString(timestamp));
            connection.setRequestProperty("X-NOVA-Nonce", nonce);
            connection.setRequestProperty("X-NOVA-Signature", signature);
            if ("POST".equals(method)) {
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = readBounded(stream);
            if (response.isEmpty()) throw new IllegalStateException("empty_pc_response:" + status);
            return new JSONObject(response);
        } finally {
            connection.disconnect();
        }
    }

    private String hmac(String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP | Base64.URL_SAFE);
    }

    private static String readBounded(InputStream stream) throws Exception {
        if (stream == null) return "";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int total = 0;
            int count;
            while ((count = in.read(buffer)) != -1) {
                total += count;
                if (total > MAX_RESPONSE_BYTES) throw new IllegalStateException("pc_response_too_large");
                out.write(buffer, 0, count);
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static String normalizeBaseUrl(String value) {
        if (value == null) return "";
        String url = value.trim();
        while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return "";
        return url;
    }

    private static String compactError(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
