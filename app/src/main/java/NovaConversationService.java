package com.aircontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated low-latency conversation pipeline. It never enters NovaBrain or the planner. */
public final class NovaConversationService {
    private static final String TAG = "NovaConversation";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final String LOCAL_ENDPOINT = "local://nova";
    private static final String DEFAULT_MODEL = "gemini-3.8-flash";
    private static final int MAX_HISTORY = 8;

    public interface Listener {
        void onStatus(String text);
        void onReply(String text);
    }

    private final Context context;
    private final NovaMemory memory;
    private final NovaSecureStore secureStore;
    private final NovaAiProviderRouter router;
    private final Handler main = new Handler(Looper.getMainLooper());

    public NovaConversationService(Context context, NovaMemory memory) {
        this.context = context.getApplicationContext();
        this.memory = memory == null ? new NovaMemory(this.context) : memory;
        this.secureStore = new NovaSecureStore(this.context);
        this.router = new NovaAiProviderRouter(this.context);
    }

    public void chat(String request, Listener listener) {
        if (request == null || request.trim().isEmpty()) return;
        final String cleanRequest = request.trim();
        final long started = System.currentTimeMillis();
        main.post(() -> { if (listener != null) listener.onStatus("NOVA • THINKING"); });

        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "system")
                    .put("content", "You are NOVA, a fast natural-language AI assistant. "
                            + "Answer the user's request directly and naturally. "
                            + "Never output JSON, tool plans, or fake device actions. "
                            + "Be concise for simple questions and helpful for explanations."));

            JSONArray history = memory.recent();
            int start = Math.max(0, history.length() - MAX_HISTORY);
            for (int i = start; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role", "");
                if ("user".equalsIgnoreCase(role) || "assistant".equalsIgnoreCase(role)) messages.put(item);
            }
            messages.put(new JSONObject().put("role", "user").put("content", cleanRequest));

            String endpoint = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(ENDPOINT, LOCAL_ENDPOINT).trim();
            String model = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(MODEL, DEFAULT_MODEL).trim();
            if (model.isEmpty() || model.startsWith("gpt-")) model = DEFAULT_MODEL;

            Log.d(TAG, "CHAT START provider=" + router.getConfiguredProvider() + " model=" + model);
            router.chat(endpoint, secureStore.getApiKey(), model, messages, new NovaAiProvider.Callback() {
                @Override public void onResult(String text) {
                    long elapsed = System.currentTimeMillis() - started;
                    String answer = text == null ? "" : text.trim();
                    if (answer.isEmpty()) answer = "I didn't get a usable response.";
                    memory.remember("user", cleanRequest);
                    memory.remember("assistant", answer);
                    Log.d(TAG, "CHAT COMPLETE " + elapsed + " ms");
                    final String finalAnswer = answer;
                    main.post(() -> {
                        if (listener != null) {
                            listener.onStatus("NOVA • READY • " + elapsed + " ms");
                            listener.onReply(finalAnswer);
                        }
                    });
                }

                @Override public void onError(String message) {
                    long elapsed = System.currentTimeMillis() - started;
                    Log.e(TAG, "CHAT FAILED " + elapsed + " ms: " + message);
                    main.post(() -> {
                        if (listener != null) listener.onReply("My AI core is unavailable right now. " + (message == null ? "" : message));
                    });
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "CHAT PREPARATION FAILED", e);
            main.post(() -> { if (listener != null) listener.onReply("I couldn't prepare the conversation."); });
        }
    }

    public void shutdown() { router.shutdown(); }
}
