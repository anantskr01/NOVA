package com.aircontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local/OpenAI-compatible AI client with bounded retry and robust response parsing. */
public final class NovaAiClient {
    public interface Callback { void onResult(String text); void onError(String message); }

    private static final String TAG = "NovaAI";
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 90000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        executor.execute(() -> {
            String lastError = "AI request failed";
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    String urlText = normalizeEndpoint(endpoint);
                    if (urlText.isEmpty()) throw new IllegalArgumentException("AI endpoint is not configured");

                    URL url = new URL(urlText);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    try {
                        connection.setRequestMethod("POST");
                        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                        connection.setReadTimeout(READ_TIMEOUT_MS);
                        connection.setDoOutput(true);
                        connection.setUseCaches(false);
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        connection.setRequestProperty("Accept", "application/json");
                        if (apiKey != null && !apiKey.trim().isEmpty()) {
                            connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                        }

                        JSONObject body = new JSONObject();
                        String cleanModel = model == null || model.trim().isEmpty() ? "qwen2.5:1.5b" : model.trim();
                        body.put("model", cleanModel);
                        body.put("messages", messages);
                        body.put("temperature", 0.2);
                        body.put("stream", false);

                        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                        connection.setFixedLengthStreamingMode(bytes.length);
                        try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }

                        int code = connection.getResponseCode();
                        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                        String response = readAll(stream);
                        if (code < 200 || code >= 300) {
                            throw new IllegalStateException("AI HTTP " + code + ": " + compact(response));
                        }

                        JSONObject json = new JSONObject(response);
                        String text = extractText(json);
                        if (text.trim().isEmpty()) throw new IllegalStateException("AI returned no text: " + compact(response));
                        String finalText = text.trim();
                        main.post(() -> callback.onResult(finalText));
                        return;
                    } finally {
                        connection.disconnect();
                    }
                } catch (SocketTimeoutException e) {
                    lastError = "AI server timed out (attempt " + attempt + "/" + MAX_ATTEMPTS + ").";
                    Log.w(TAG, lastError, e);
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? "AI request failed" : e.getMessage();
                    Log.w(TAG, "AI attempt " + attempt + " failed: " + lastError, e);
                    if (attempt == MAX_ATTEMPTS || lastError.startsWith("AI HTTP 4")) break;
                }

                try { Thread.sleep(400L * attempt); } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            final String error = lastError + " Make sure the local AI server is running and the tablet can reach the configured endpoint.";
            main.post(() -> callback.onError(error));
        });
    }

    private String normalizeEndpoint(String endpoint) {
        if (endpoint == null) return "";
        String value = endpoint.trim().replaceFirst("/+$", "");
        if (value.isEmpty()) return "";
        if (value.endsWith("/api/chat")) return value;
        if (value.endsWith("/api")) return value + "/chat";
        return value + "/api/chat";
    }

    private String extractText(JSONObject json) {
        JSONArray choices = json.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            if (message != null) {
                String content = message.optString("content", "");
                if (!content.isEmpty()) return content;
            }
        }
        JSONObject message = json.optJSONObject("message");
        if (message != null) {
            String content = message.optString("content", "");
            if (!content.isEmpty()) return content;
        }
        return json.optString("response", "");
    }

    private String compact(String value) {
        if (value == null) return "";
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 500 ? cleaned.substring(0, 500) + "…" : cleaned;
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) builder.append(line);
        }
        return builder.toString();
    }

    public void shutdown() { executor.shutdownNow(); }
}
