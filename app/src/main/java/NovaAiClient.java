package com.aircontrol;

import org.json.JSONArray;

/** Compatibility gateway. Provider-specific transport is delegated to NovaAiProviderRouter. */
public final class NovaAiClient {
    public interface Callback { void onResult(String text); void onError(String message); }

    private final NovaAiProviderRouter router;

    public NovaAiClient() { router = new NovaAiProviderRouter(); }

    public NovaAiClient(android.content.Context context) { router = new NovaAiProviderRouter(context); }

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        router.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
            @Override public void onResult(String text) { callback.onResult(text); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    public String getProvider() { return router.getConfiguredProvider(); }

    public boolean setProvider(String provider) { return router.setConfiguredProvider(provider); }

    public void shutdown() { router.shutdown(); }
}
