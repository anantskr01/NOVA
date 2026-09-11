package com.aircontrol;

import android.content.Context;
import org.json.JSONArray;

/** Compatibility gateway used by NovaBrain; delegates provider selection to NovaAiProviderRouter. */
public final class NovaAiClient {
    public interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    private NovaAiProviderRouter router;

    /** Kept for compatibility with older callers. Context is required for provider preferences. */
    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        throw new IllegalStateException("NovaAiClient requires a Context; use NovaAiClient(Context)");
    }

    public NovaAiClient(Context context) {
        router = new NovaAiProviderRouter(context);
    }

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, NovaAiProvider.Callback callback) {
        router.chat(endpoint, apiKey, model, messages, callback);
    }

    public void shutdown() {
        if (router != null) router.shutdown();
    }
}
