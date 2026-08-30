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

/** Local/OpenAI-compatible AI client. No API key is bundled in the APK. */
public final class NovaAiClient {
    public interface Callback { void onResult(String text); void onError(String message); }

    private static final String TAG = "NovaAI";
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        executor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String urlText = endpoint == null ? "" : endpoint.trim();
                if (urlText.isEmpty()) throw new IllegalArgumentException("AI endpoint is not configured");
                URL url = new URL(urlText);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(45000);
                connection.setDoOutput(true);
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
                // Ollama streams by default. Disabling streaming keeps the response easy to parse
                // and also works with OpenAI-compatible local servers.
                body.put("stream", false);

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (java.io.OutputStream out = connection.getOutputStream()) { out.write(bytes); }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) throw new IllegalStateException("AI HTTP " + code + ": " + compact(response));

                JSONObject json = new JSONObject(response);
                String text = extractText(json);
                if (text.trim().isEmpty()) throw new IllegalStateException("AI returned no text: " + compact(response));
                String finalText = text.trim();
                main.post(() -> callback.onResult(finalText));
            } catch (SocketTimeoutException e) {
                Log.e(TAG, "AI request timed out", e);
                main.post(() -> callback.onError("AI server timed out. Make sure Ollama is running and reachable from the tablet."));
            } catch (Exception e) {
                Log.e(TAG, "AI request failed", e);
                String message = e.getMessage() == null ? "AI request failed" : e.getMessage();
                main.post(() -> callback.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    /** Supports both OpenAI chat responses and native Ollama /api/chat responses. */
    private String extractText(JSONObject json) {
        // OpenAI-compatible: {"choices":[{"message":{"content":"..."}}]}
        JSONArray choices = json.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            if (message != null) {
                String content = message.optString("content", "");
                if (!content.isEmpty()) return content;
            }
        }

        // Ollama native: {"message":{"role":"assistant","content":"..."}}
        JSONObject message = json.optJSONObject("message");
        if (message != null) {
            String content = message.optString("content", "");
            if (!content.isEmpty()) return content;
        }

        // Some compatible local servers return a direct response field.
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
