package com.aircontrol;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** AI gateway. Routes ordinary conversation through a lightweight path and preserves the agent path for actions. */
public final class NovaAiClient {
    public interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    private static final String TAG = "NovaAiClient";
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
        if (isFastConversation(messages)) {
            JSONArray fastMessages = buildFastMessages(messages);
            long started = System.currentTimeMillis();
            Log.d(TAG, "FAST CONVERSATION PATH");
            router.chat(endpoint, apiKey, model, fastMessages, new NovaAiProvider.Callback() {
                @Override public void onResult(String text) {
                    long elapsed = System.currentTimeMillis() - started;
                    Log.d(TAG, "FAST CONVERSATION RESPONSE " + elapsed + " ms");
                    callback.onResult(wrapConversation(text));
                }

                @Override public void onError(String message) {
                    Log.e(TAG, "FAST CONVERSATION FAILED: " + message);
                    callback.onError(message);
                }
            });
            return;
        }

        router.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
            @Override public void onResult(String text) { callback.onResult(text); }
            @Override public void onError(String message) { callback.onError(message); }
        });
    }

    private boolean isFastConversation(JSONArray messages) {
        if (messages == null || messages.length() == 0) return false;
        String request = "";
        for (int i = messages.length() - 1; i >= 0; i--) {
            JSONObject item = messages.optJSONObject(i);
            if (item != null && "user".equalsIgnoreCase(item.optString("role", ""))) {
                request = item.optString("content", "").trim().toLowerCase(java.util.Locale.ROOT);
                break;
            }
        }
        if (request.isEmpty()) return false;

        String[] agentSignals = {
                "open ", "launch ", "start ", "close ", "send ", "text ", "message ", "call ",
                "tap ", "click ", "swipe ", "scroll ", "go home", "go back", "recent apps",
                "notifications", "quick settings", "settings", "turn on ", "turn off ",
                "enable ", "disable ", "set ", "change ", "play ", "pause ", "stop ",
                "download ", "install ", "book ", "order ", "buy ", "schedule ",
                "take a screenshot", "take screenshot", "read screen", "search for ",
                "search ", "google "
        };
        for (String signal : agentSignals) {
            if (request.startsWith(signal) || request.contains(" " + signal.trim() + " ")) return false;
        }
        return true;
    }

    private JSONArray buildFastMessages(JSONArray original) {
        JSONArray out = new JSONArray();
        try {
            out.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "You are NOVA, a fast natural-language AI assistant. "
                            + "Answer the user's request directly and naturally. "
                            + "Do not output JSON, tool plans, or fake device actions. "
                            + "Be concise for simple questions and helpful for explanations."));

            int kept = 0;
            for (int i = original.length() - 1; i >= 0 && kept < 8; i--) {
                JSONObject item = original.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role", "");
                if (!"user".equalsIgnoreCase(role) && !"assistant".equalsIgnoreCase(role)) continue;
                out.put(1, item);
                kept++;
            }
        } catch (Exception e) {
            Log.e(TAG, "FAST MESSAGE BUILD FAILED", e);
        }
        return out;
    }

    private String wrapConversation(String text) {
        try {
            return new JSONObject()
                    .put("say", text == null ? "" : text.trim())
                    .put("actions", new JSONArray())
                    .toString();
        } catch (Exception e) {
            return "{\"say\":\"\",\"actions\":[]}";
        }
    }

    public void shutdown() {
        router.shutdown();
    }
}
