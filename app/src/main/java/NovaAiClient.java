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

/** NOVA AI gateway. Supports Gemini API natively and keeps OpenAI-compatible HTTP as a fallback. */
public final class NovaAiClient {
    public interface Callback { void onResult(String text); void onError(String message); }

    private static final String TAG = "NovaAI";
    private static final int MAX_ATTEMPTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 90000;
    private static final String DEFAULT_GEMINI_ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.7-flash:generateContent";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        if (isGeminiEndpoint(endpoint) || isGeminiModel(model)) {
            requestGemini(endpoint, apiKey, model, messages, callback);
            return;
        }
        requestOpenAiCompatible(endpoint, apiKey, model, messages, callback);
    }

    private void requestGemini(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        executor.execute(() -> {
            String lastError = "Gemini request failed";
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                HttpURLConnection connection = null;
                try {
                    if (apiKey == null || apiKey.trim().isEmpty()) {
                        throw new IllegalArgumentException("Gemini API key is not configured");
                    }

                    String urlText = normalizeGeminiEndpoint(endpoint, model);
                    connection = (HttpURLConnection) new URL(urlText).openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setDoOutput(true);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("x-goog-api-key", apiKey.trim());

                    JSONObject body = buildGeminiBody(messages);
                    byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(bytes.length);
                    try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }

                    int code = connection.getResponseCode();
                    InputStream stream = code >= 200 && code < 300
                            ? connection.getInputStream() : connection.getErrorStream();
                    String response = readAll(stream);
                    if (code < 200 || code >= 300) {
                        throw new IllegalStateException("Gemini HTTP " + code + ": " + compact(response));
                    }

                    String text = extractGeminiText(new JSONObject(response));
                    if (text.trim().isEmpty()) {
                        throw new IllegalStateException("Gemini returned no text: " + compact(response));
                    }
                    String finalText = text.trim();
                    main.post(() -> callback.onResult(finalText));
                    return;
                } catch (SocketTimeoutException e) {
                    lastError = "Gemini timed out (attempt " + attempt + "/" + MAX_ATTEMPTS + ").";
                    Log.w(TAG, lastError, e);
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? "Gemini request failed" : e.getMessage();
                    Log.w(TAG, "Gemini attempt " + attempt + " failed: " + lastError, e);
                    if (attempt == MAX_ATTEMPTS || lastError.startsWith("Gemini HTTP 4")) break;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                sleepBeforeRetry(attempt);
            }
            final String error = lastError + " Check the Gemini API key, network connection, and free-tier quota.";
            main.post(() -> callback.onError(error));
        });
    }

    private JSONObject buildGeminiBody(JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        JSONArray contents = new JSONArray();
        String systemInstruction = "";

        for (int i = 0; i < messages.length(); i++) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null) continue;
            String role = message.optString("role", "user");
            String content = message.optString("content", "");
            if (content.trim().isEmpty()) continue;

            if ("system".equalsIgnoreCase(role)) {
                if (!systemInstruction.isEmpty()) systemInstruction += "\n\n";
                systemInstruction += content;
                continue;
            }

            JSONObject item = new JSONObject();
            item.put("role", "assistant".equalsIgnoreCase(role) ? "model" : "user");
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", content));
            item.put("parts", parts);
            contents.put(item);
        }

        body.put("contents", contents);
        if (!systemInstruction.isEmpty()) {
            body.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject().put("text", systemInstruction))));
        }

        JSONObject generationConfig = new JSONObject();
        generationConfig.put("temperature", 0.2);
        body.put("generationConfig", generationConfig);
        return body;
    }

    private String extractGeminiText(JSONObject json) {
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return "";
        JSONObject candidate = candidates.optJSONObject(0);
        JSONObject content = candidate == null ? null : candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) return "";

        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null) {
                String text = part.optString("text", "");
                if (!text.isEmpty()) {
                    if (result.length() > 0) result.append('\n');
                    result.append(text);
                }
            }
        }
        return result.toString();
    }

    private void requestOpenAiCompatible(String endpoint, String apiKey, String model,
                                         JSONArray messages, Callback callback) {
        executor.execute(() -> {
            String lastError = "AI request failed";
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                HttpURLConnection connection = null;
                try {
                    String urlText = normalizeEndpoint(endpoint);
                    if (urlText.isEmpty()) throw new IllegalArgumentException("AI endpoint is not configured");

                    connection = (HttpURLConnection) new URL(urlText).openConnection();
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

                    String text = extractText(new JSONObject(response));
                    if (text.trim().isEmpty()) throw new IllegalStateException("AI returned no text: " + compact(response));
                    String finalText = text.trim();
                    main.post(() -> callback.onResult(finalText));
                    return;
                } catch (SocketTimeoutException e) {
                    lastError = "AI server timed out (attempt " + attempt + "/" + MAX_ATTEMPTS + ").";
                    Log.w(TAG, lastError, e);
                } catch (Exception e) {
                    lastError = e.getMessage() == null ? "AI request failed" : e.getMessage();
                    Log.w(TAG, "AI attempt " + attempt + " failed: " + lastError, e);
                    if (attempt == MAX_ATTEMPTS || lastError.startsWith("AI HTTP 4")) break;
                } finally {
                    if (connection != null) connection.disconnect();
                }
                sleepBeforeRetry(attempt);
            }
            final String error = lastError + " Check the configured AI endpoint and network connection.";
            main.post(() -> callback.onError(error));
        });
    }

    private String normalizeGeminiEndpoint(String endpoint, String model) {
        if (endpoint != null && endpoint.trim().startsWith("https://generativelanguage.googleapis.com/")) {
            String value = endpoint.trim();
            if (value.contains(":generateContent")) return value;
            if (value.endsWith("/v1beta")) {
                String cleanModel = model == null || model.trim().isEmpty() ? "gemini-3.7-flash" : model.trim();
                return value + "/models/" + cleanModel + ":generateContent";
            }
            return value + ":generateContent";
        }
        String cleanModel = model == null || model.trim().isEmpty() ? "gemini-3.7-flash" : model.trim();
        return "https://generativelanguage.googleapis.com/v1beta/models/" + cleanModel + ":generateContent";
    }

    private boolean isGeminiEndpoint(String endpoint) {
        return endpoint != null && endpoint.contains("generativelanguage.googleapis.com");
    }

    private boolean isGeminiModel(String model) {
        return model != null && model.toLowerCase(java.util.Locale.ROOT).startsWith("gemini-");
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

    private void sleepBeforeRetry(int attempt) {
        try { Thread.sleep(400L * attempt); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
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

    public void shutdown() {
        executor.shutdownNow();
    }
}
