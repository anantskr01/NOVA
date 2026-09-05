package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;

/** OpenAI-compatible /v1/chat/completions provider adapter. */
public final class NovaOpenAiCompatibleProvider extends NovaHttpAiProvider {
    @Override public String id() { return "openai-compatible"; }

    @Override
    public boolean supports(String endpoint) {
        String e = endpoint == null ? "" : endpoint.trim().toLowerCase();
        return e.contains("/v1") || e.contains("chat/completions");
    }

    @Override
    protected String buildUrl(String endpoint) {
        String value = endpoint == null ? "" : endpoint.trim().replaceFirst("/+$", "");
        if (value.endsWith("/chat/completions")) return value;
        if (value.endsWith("/v1")) return value + "/chat/completions";
        if (value.endsWith("/v1/chat")) return value + "/completions";
        return value + "/v1/chat/completions";
    }

    @Override
    protected JSONObject buildRequest(String model, JSONArray messages) throws Exception {
        JSONObject body = new JSONObject();
        body.put("model", model == null || model.trim().isEmpty() ? "gpt-4o-mini" : model.trim());
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("stream", false);
        return body;
    }

    @Override
    protected String extractText(JSONObject response) {
        JSONArray choices = response.optJSONArray("choices");
        if (choices != null && choices.length() > 0) {
            JSONObject choice = choices.optJSONObject(0);
            JSONObject message = choice == null ? null : choice.optJSONObject("message");
            if (message != null) return message.optString("content", "");
        }
        return response.optString("response", "");
    }
}
