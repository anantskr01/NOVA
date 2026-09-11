package com.aircontrol;

import org.json.JSONArray;

/** Compatibility gateway used by NovaBrain; delegates provider selection to NovaAiProviderRouter. */
public final class NovaAiClient {
    public interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    private final NovaAiProviderRouter router;

    /** Legacy constructor retained for NovaBrain compatibility. */
    public NovaAiClient() {
        router = new NovaAiProviderRouter();
    }

    /** Context-aware constructor for newer callers. */
    public NovaAiClient(android.content.Context context) {
        router = new NovaAiProviderRouter(context);
    }

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        router.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
            @Override public void onResult(String text) { callback.onResult(text); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    public void shutdown() {
        router.shutdown();
    }
}
