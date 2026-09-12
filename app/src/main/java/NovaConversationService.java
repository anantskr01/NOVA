package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Fast conversational path. Normal chat never enters the autonomous task/planner loop. */
public final class NovaConversationService {
    private static final String TAG = "NovaConversation";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final String LOCAL_ENDPOINT = "local://nova";
    private static final String DEFAULT_MODEL = "gemini-3.8-flash";
    private static final int MAX_HISTORY = 12;

    public interface Listener {
        void onStatus(String text);
        void onReply(String text);
    }

    private final Context context;
    private final NovaMemory memory;
    private final NovaSecureStore secureStore;
    private final NovaAiClient ai;

    public NovaConversationService(Context context, NovaMemory memory) {
        this.context = context.getApplicationContext();
        this.memory = memory == null ? new NovaMemory(this.context) : memory;
        this.secureStore = new NovaSecureStore(this.context);
        this.ai = new NovaAiClient(this.context);
    }

    public void chat(String request, Listener listener) {
        if (request == null || request.trim().isEmpty()) return;

        String endpoint = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ENDPOINT, LOCAL_ENDPOINT).trim();
        String model = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MODEL, DEFAULT_MODEL).trim();
        if (model.isEmpty() || model.startsWith("gpt-")) model = DEFAULT_MODEL;

        JSONArray messages = new JSONArray();
        try {
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "You are NOVA, a fast natural-language Android assistant. "
                            + "Answer the user's question directly and naturally. "
                            + "Do not return JSON, tool plans, or pretend to execute device actions. "
                            + "Keep simple answers concise. If the user asks for an Android action, explain that it should be handled by NOVA's agent/action system instead of claiming it was done."));

            JSONArray history = memory.recent();
            int start = Math.max(0, history.length() - MAX_HISTORY);
            for (int i = start; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item != null) messages.put(item);
            }

            messages.put(new JSONObject().put("role", "user").put("content", request.trim()));
        } catch (Exception e) {
            if (listener != null) listener.onReply("I couldn't prepare the conversation.");
            return;
        }

        if (listener != null) listener.onStatus("NOVA • THINKING");
        final long started = System.currentTimeMillis();
        ai.chat(endpoint, secureStore.getApiKey(), model, messages, new NovaAiClient.Callback() {
            @Override public void onResult(String text) {
                long elapsed = System.currentTimeMillis() - started;
                Log.d(TAG, "Conversation response in " + elapsed + " ms");
                String answer = text == null ? "" : text.trim();
                if (answer.isEmpty()) answer = "I didn't get a usable response.";
                memory.remember("user", request.trim());
                memory.remember("assistant", answer);
                if (listener != null) {
                    listener.onStatus("NOVA • READY • " + elapsed + " ms");
                    listener.onReply(answer);
                }
            }

            @Override public void onError(String message) {
                long elapsed = System.currentTimeMillis() - started;
                Log.e(TAG, "Conversation failed after " + elapsed + " ms: " + message);
                if (listener != null) {
                    listener.onStatus("NOVA • AI ERROR");
                    listener.onReply("My AI core is unavailable right now. " + (message == null ? "" : message));
                }
            }
        });
    }

    public void shutdown() {
        ai.shutdown();
    }
}
