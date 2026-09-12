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

/** Gemini cloud provider using Google's REST API. API keys are supplied at runtime and never stored here. */
public final class NovaGeminiAiProvider implements NovaAiProvider {
    private static final String TAG = "NovaGeminiAI";
    private static final String DEFAULT_MODEL = "gemini-3.8-flash";
    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 90000;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override public String getId() { return "gemini"; }

    @Override
    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        executor.execute(() -> {
            try {
                String key = apiKey == null ? "" : apiKey.trim();
                if (key.isEmpty()) throw new IllegalArgumentException("Gemini API key is not configured");

                String selectedModel = model == null || model.trim().isEmpty()
                        ? DEFAULT_MODEL : model.trim();
                String urlText = BASE_URL + selectedModel + ":generateContent?key=" +
                        java.net.URLEncoder.encode(key, "UTF-8");

                HttpURLConnection connection = (HttpURLConnection) new URL(urlText).openConnection();
                try {
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                    connection.setReadTimeout(READ_TIMEOUT_MS);
                    connection.setDoOutput(true);
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    connection.setRequestProperty("Accept", "application/json");

                    JSONObject body = new JSONObject();
                    JSONArray contents = new JSONArray();
                    for (int i = 0; i < messages.length(); i++) {
                        JSONObject message = messages.optJSONObject(i);
                        if (message == null) continue;
                        String role = message.optString("role", "user");
                        String content = message.optString("content", "");
                        if (content.isEmpty()) continue;

                        JSONObject item = new JSONObject();
                        item.put("role", "system".equals(role) ? "user" :
                                ("assistant".equals(role) ? "model" : "user"));
                        JSONArray parts = new JSONArray();
                        parts.put(new JSONObject().put("text", content));
                        item.put("parts", parts);
                        contents.put(item);
                    }
                    body.put("contents", contents);
                    body.put("generationConfig", new JSONObject().put("temperature", 0.2));

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

                    String text = extractText(new JSONObject(response)).trim();
                    if (text.isEmpty()) throw new IllegalStateException("Gemini returned no text");
                    main.post(() -> callback.onResult(text));
                } finally {
                    connection.disconnect();
                }
            } catch (SocketTimeoutException e) {
                Log.w(TAG, "Gemini request timed out", e);
                main.post(() -> callback.onError("Gemini request timed out."));
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Gemini request failed" : e.getMessage();
                Log.w(TAG, "Gemini request failed: " + message, e);
                main.post(() -> callback.onError(message));
            }
        });
    }

    private String extractText(JSONObject json) {
        JSONArray candidates = json.optJSONArray("candidates");
        if (candidates == null || candidates.length() == 0) return "";
        JSONObject candidate = candidates.optJSONObject(0);
        JSONObject content = candidate == null ? null : candidate.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        if (parts == null) return "";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null) result.append(part.optString("text", ""));
        }
        return result.toString();
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

    @Override public void shutdown() { executor.shutdownNow(); }
}
