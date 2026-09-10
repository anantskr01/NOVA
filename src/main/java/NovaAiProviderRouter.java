package com.aircontrol;

import android.content.Context;
import org.json.JSONArray;

/**
 * Selects the AI backend for NOVA without exposing provider details to NovaBrain.
 *
 * Modes:
 *   auto  - local first, HTTP fallback (current NOVA behavior)
 *   local - local provider only
 *   http  - HTTP provider only
 *   <id>  - reserved for future providers such as Gemini
 */
public final class NovaAiProviderRouter implements NovaAiProvider {
    private static final String PREFS = "nova_ai_settings";
    private static final String PROVIDER = "ai_provider";

    private final Context context;
    private final NovaLocalAiProvider local = new NovaLocalAiProvider();
    private final NovaHttpAiProvider http = new NovaHttpAiProvider();

    public NovaAiProviderRouter(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public String getId() {
        return "router";
    }

    @Override
    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        String mode = configuredProvider();
        if ("local".equals(mode)) {
            local.chat(endpoint, apiKey, model, messages, callback);
            return;
        }
        if ("http".equals(mode)) {
            http.chat(endpoint, apiKey, model, messages, callback);
            return;
        }
        if ("auto".equals(mode) || mode.isEmpty()) {
            local.chat(endpoint, apiKey, model, messages, new Callback() {
                @Override
                public void onResult(String text) {
                    callback.onResult(text);
                }

                @Override
                public void onError(String message) {
                    http.chat(endpoint, apiKey, model, messages, callback);
                }
            });
            return;
        }

        callback.onError("Unknown AI provider: " + mode);
    }

    private String configuredProvider() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PROVIDER, "auto")
                .trim()
                .toLowerCase(java.util.Locale.US);
    }

    @Override
    public void shutdown() {
        local.shutdown();
        http.shutdown();
    }
}
