package com.aircontrol;

import org.json.JSONArray;

/**
 * Provider-neutral contract for NOVA's reasoning model.
 * NovaBrain depends on this interface rather than a specific AI runtime or vendor.
 */
public interface NovaAiProvider {
    interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    default String getId() {
        return "unknown";
    }

    void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback);

    void shutdown();
}
