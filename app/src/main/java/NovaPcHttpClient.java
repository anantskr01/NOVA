package com.aircontrol;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticated HTTP transport for the NOVA PC companion. */
public final class NovaPcHttpClient implements NovaPcAgent {
    private final URI baseUri;
    private final byte[] token;
    private final int timeoutMs;

    public NovaPcHttpClient(String baseUrl, String token) {
        this(baseUrl, token, 10_000);
    }

    public NovaPcHttpClient(String baseUrl, String token, int timeoutMs) {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("PC token must be at least 32 characters");
        this.baseUri = URI.create(baseUrl.endsWith("/") ? baseUrl : baseUrl + "/");
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.timeoutMs = timeoutMs;
    }

    /** Unauthenticated liveness check for the PC companion setup screen. */
    public void healthCheck() throws IOException {
        HttpURLConnection connection = (HttpURLConnection) baseUri.resolve("v1/health").toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300 || !response.contains("\"ok\":true")) {
            throw new IOException("PC companion health check failed (HTTP " + status + ")");
        }
    }

    @Override public String readFile(String path) throws IOException {
        return post("v1/fs/read", "{\"path\":" + quote(path) + "}");
    }

    @Override public void writeFile(String path, String content) throws IOException {
        post("v1/fs/write", "{\"path\":" + quote(path) + ",\"content\":" + quote(content) + "}");
    }

    @Override public String listFiles(String path) throws IOException {
        return post("v1/fs/list", "{\"path\":" + quote(path) + "}");
    }

    @Override public ProcessResult runAllowed(String command) throws IOException {
        String response = post("v1/process/run", "{\"command\":" + quote(command) + "}");
        return new ProcessResult(intField(response, "exitCode"), stringField(response, "output"));
    }

    private String post(String route, String body) throws IOException {
        URI uri = baseUri.resolve(route);
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String nonce = UUID.randomUUID().toString();
        String canonical = "POST\n" + uri.getPath() + "\n" + timestamp + "\n" + nonce + "\n" + body;
        String signature = hmac(canonical);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        connection.setRequestProperty("X-NOVA-Timestamp", timestamp);
        connection.setRequestProperty("X-NOVA-Nonce", nonce);
        connection.setRequestProperty("X-NOVA-Signature", signature);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        connection.getOutputStream().write(payload);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String response = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        if (status < 200 || status >= 300) throw new IOException("PC companion HTTP " + status + ": " + response);
        return response;
    }

    private String hmac(String value) throws IOException {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IOException("Unable to sign PC request", e); }
    }

    private static int intField(String json, String key) throws IOException {
        String marker = "\"" + key + "\":";
        int start = json.indexOf(marker);
        if (start < 0) throw new IOException("Missing response field: " + key);
        start += marker.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        try { return Integer.parseInt(json.substring(start, end)); } catch (Exception e) { throw new IOException("Invalid response field: " + key, e); }
    }

    private static String stringField(String json, String key) throws IOException {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) throw new IOException("Missing response field: " + key);
        start += marker.length();
        StringBuilder out = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { out.append(switch (c) { case 'n' -> '\n'; case 'r' -> '\r'; case 't' -> '\t'; default -> c; }); escaped = false; }
            else if (c == '\\') escaped = true;
            else if (c == '"') return out.toString();
            else out.append(c);
        }
        throw new IOException("Unterminated response field: " + key);
    }

    private static String quote(String value) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : value.toCharArray()) switch (c) {
            case '\\' -> b.append("\\\\"); case '"' -> b.append("\\\""); case '\n' -> b.append("\\n"); case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t"); default -> b.append(c);
        }
        return b.append('"').toString();
    }
}
