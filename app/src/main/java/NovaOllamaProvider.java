package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;

/** Native Ollama /api/chat provider. */
public final class NovaOllamaProvider extends NovaHttpAiProvider {
    @Override public String id() { return "ollama"; }

    @Override
    public boolean supports(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim().toLowerCase();
        if (e.isEmpty()) return false;
        // /v1 is reserved for OpenAI-compatible providers. Ollama's native
        // endpoint is /api/chat (or an Ollama host that will receive /api/chat).
        return !e.contains("/v1");
    }

    @Override
    protected String buildUrl(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim().replaceFirst("/+$", "");
        if (value.endsWith("/api/chat")) return value;
        if (value.endsWith("/api")) return value + "/chat";
        return value + "/api/chat";
    }

    @Override
    protected JSONObject buildRequest(String model, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model == null || model.trim().isEmpty() ? "qwen2.5:1.5b" : model.trim());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("stream", false);
        return body;
    }

    @Override
    protected String extractText(JSONObject response) {
        JSONObject message = response.optJSONObject("message");
        if (message != null) return message.optString("content", "");
        return response.optString("response", "");
    }
}
