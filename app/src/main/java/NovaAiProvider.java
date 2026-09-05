package com.aircontrol;

import org.json.JSONArray;

/** Provider-neutral contract for NOVA's reasoning model backends. */
public interface NovaAiProvider {
    interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    String id();
    boolean supports(String endpoint);
    void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback);
    void shutdown();
}
