package com.aircontrol;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** AI gateway for the autonomous agent path. Normal conversation now bypasses this class. */
public final class NovaAiClient {
    public interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    private static final String TAG = "NovaAiClient";
    private final NovaAiProviderRouter router;

    public NovaAiClient() { router = new NovaAiProviderRouter(); }
    public NovaAiClient(android.content.Context context) { router = new NovaAiProviderRouter(context); }

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        final long started = System.currentTimeMillis();
        Log.d(TAG, "AGENT AI REQUEST START messages=" + (messages == null ? 0 : messages.length()));
        router.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
            @Override public void onResult(String text) {
                Log.d(TAG, "AGENT AI REQUEST COMPLETE " + (System.currentTimeMillis() - started) + " ms");
                callback.onResult(text);
            }
            @Override public void onError(String message) {
                Log.e(TAG, "AGENT AI REQUEST FAILED " + (System.currentTimeMillis() - started) + " ms: " + message);
                callback.onError(message);
            }
        });
    }

    public void shutdown() { router.shutdown(); }
}
