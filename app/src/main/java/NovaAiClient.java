package com.aircontrol;

import org.json.JSONArray;

/** Backward-compatible AI facade. Provider selection is delegated to NovaAiProviderManager. */
public final class NovaAiClient {
    public interface Callback { void onResult(String text); void onError(String message); }

    private final NovaAiProviderManager providers = new NovaAiProviderManager();

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        providers.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
            @Override public void onResult(String text) { callback.onResult(text); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    public String providerId(String endpoint) { return providers.providerId(endpoint); }
    public String providerSummary() { return providers.describe(); }
    public void shutdown() { providers.shutdown(); }
}
