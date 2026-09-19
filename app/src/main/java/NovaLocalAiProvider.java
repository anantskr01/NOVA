package com.aircontrol;

import org.json.JSONArray;

/** Local on-device AI provider backed by the bundled llama.cpp runtime. */
public final class NovaLocalAiProvider implements NovaAiProvider {
    private final LocalModelRuntime runtime = new LocalModelRuntime();

    @Override
    public String getId() {
        return "local";
    }

    @Override
    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        try {
            runtime.tryChat(messages, new LocalModelRuntime.Callback() {
                @Override
                public void onResult(String text) {
                    callback.onResult(text);
                }

                @Override
                public void onError(String message) {
                    callback.onError(message);
                }
            });
        } catch (Throwable t) {
            callback.onError(t.getMessage() == null ? "Local AI provider failed" : t.getMessage());
        }
    }

    @Override
    public void shutdown() {
        runtime.shutdown();
    }
}
