package com.aircontrol;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** OpenAI-compatible AI client. No API key is bundled in the APK. */
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
                connection.setConnectTimeout(12000);
                connection.setReadTimeout(30000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setRequestProperty("Accept", "application/json");
                if (apiKey != null && !apiKey.trim().isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
                }

                boolean responsesApi = isResponsesEndpoint(urlText);
                JSONObject body = new JSONObject();
                body.put("model", model == null || model.trim().isEmpty() ? "gpt-5.6-luna" : model.trim());

                if (responsesApi) {
                    // OpenAI Responses API: the same conversation is supplied as input items.
                    body.put("input", messages);
                } else {
                    // Keep compatibility with OpenAI-compatible Chat Completions endpoints.
                    body.put("messages", messages);
                    body.put("temperature", 0.2);
                }

                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String response = readAll(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("AI HTTP " + code + ": " + response);
                }

                JSONObject json = new JSONObject(response);
                String text = responsesApi ? extractResponsesText(json) : extractChatText(json);
                if (text.trim().isEmpty()) throw new IllegalStateException("AI returned no text");

                String finalText = text.trim();
                main.post(() -> callback.onResult(finalText));
            } catch (Exception e) {
                Log.e(TAG, "AI request failed", e);
                String message = e.getMessage() == null ? "AI request failed" : e.getMessage();
                main.post(() -> callback.onError(message));
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private boolean isResponsesEndpoint(String endpoint) {
        String normalized = endpoint == null ? "" : endpoint.trim().toLowerCase();
        return normalized.endsWith("/responses") || normalized.contains("/v1/responses?");
    }

    private String extractChatText(JSONObject json) {
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) return "";
        JSONObject choice = choices.optJSONObject(0);
        JSONObject message = choice == null ? null : choice.optJSONObject("message");
        return message == null ? "" : message.optString("content", "");
    }

    private String extractResponsesText(JSONObject json) {
        // The Responses API exposes output_text in SDKs; raw HTTP responses contain
        // output message items whose content parts have type=output_text.
        String direct = json.optString("output_text", "");
        if (!direct.trim().isEmpty()) return direct;

        JSONArray output = json.optJSONArray("output");
        if (output == null) return "";

        StringBuilder text = new StringBuilder();
        for (int i = 0; i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j = 0; j < content.length(); j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                if ("output_text".equals(part.optString("type", ""))) {
                    String value = part.optString("text", "");
                    if (!value.isEmpty()) {
                        if (text.length() > 0) text.append('\n');
                        text.append(value);
                    }
                }
            }
        }
        return text.toString();
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
