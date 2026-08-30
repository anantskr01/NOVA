package com.aircontrol;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Central NOVA reasoning/orchestration layer.
 * Keeps AI reasoning separate from Android action execution.
 */
public final class NovaBrain {
    private static final String TAG = "NovaBrain";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final int MAX_HISTORY = 14;

    public interface Listener {
        void onStatus(String text);
        void onReply(String text);
    }

    private final Context context;
    private final Listener listener;
    private final NovaMemory memory;
    private final NovaSecureStore secureStore;
    private final NovaAiClient ai = new NovaAiClient();
    private final NovaActionEngine actions;
    private final NovaAgentPlanner planner;
    private boolean thinking;

    public NovaBrain(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        memory = new NovaMemory(this.context);
        secureStore = new NovaSecureStore(this.context);

        actions = new NovaActionEngine(this.context, new NovaActionEngine.Callback() {
            @Override public void status(String text) { NovaBrain.this.status(text); }
            @Override public void reply(String text) { NovaBrain.this.reply(text); }
        });

        planner = new NovaAgentPlanner(new NovaAgentPlanner.ActionExecutor() {
            @Override public boolean execute(String type, String value) {
                return actions.execute(type, value);
            }

            @Override public String readScreen() {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service == null
                        ? "Accessibility service is not connected."
                        : service.getVisibleTextSummary();
            }

            @Override public boolean clickText(String text) {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service != null && service.clickText(text);
            }

            @Override public boolean clickVisibleIndex(int index) {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service != null && service.clickVisibleIndex(index);
            }
        }, new NovaAgentPlanner.Listener() {
            @Override public void status(String text) { NovaBrain.this.status(text); }
            @Override public void reply(String text) { NovaBrain.this.reply(text); }
        });
    }

    /** Main entry point for open-ended NOVA requests. */
    public synchronized void think(String request) {
        if (request == null || request.trim().isEmpty()) return;
        if (thinking) {
            reply("I'm still working on the previous request.");
            return;
        }

        final String command = request.trim();
        if (getEndpoint().isEmpty()) {
            reply("My AI core isn't configured yet.");
            return;
        }

        thinking = true;
        memory.remember("user", command);
        status("BRAIN • UNDERSTANDING");
        askAi(command);
    }

    private void askAi(String command) {
        status("BRAIN • AI THINKING");

        try {
            JSONArray messages = new JSONArray();

            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content",
                    "You are NOVA, a careful Android tablet agent. " +
                    "Understand the user's goal and return a safe, bounded plan as JSON only. " +
                    "Format: {\"say\":\"short response\",\"actions\":[{\"type\":\"action\",\"value\":\"value\"}]}. " +
                    "Allowed actions: home, back, recents, notifications, quick_settings, " +
                    "scroll_up, scroll_down, swipe_left, swipe_right, open_url, open_package, " +
                    "open_app, click_text, click_index, search, read_screen, settings, none. " +
                    "Use at most 8 actions. Use read_screen before ambiguous UI interactions. " +
                    "Never bypass permissions, authentication, security, or private data. " +
                    "Never claim success unless an action can actually be dispatched. Prefer reversible actions. " +
                    "Do not invent tools or action types. If the task cannot be completed with the available actions, " +
                    "explain the limitation in say and return no unsafe actions.");
            messages.put(system);

            JSONObject contextMessage = new JSONObject();
            contextMessage.put("role", "system");
            contextMessage.put("content", "Saved NOVA memory:\n" + memory.factsSummary() +
                    "\n\nCurrent UI:\n" + getUiSnapshot());
            messages.put(contextMessage);

            JSONArray history = memory.recent();
            int start = Math.max(0, history.length() - MAX_HISTORY);
            for (int i = start; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item != null) messages.put(item);
            }

            ai.chat(getEndpoint(), secureStore.getApiKey(), getModel(), messages,
                    new NovaAiClient.Callback() {
                        @Override public void onResult(String text) {
                            try {
                                memory.remember("assistant", text);
                                status("BRAIN • PLAN RECEIVED");
                                if (!planner.execute(text)) reply(text);
                            } finally {
                                synchronized (NovaBrain.this) { thinking = false; }
                            }
                        }

                        @Override public void onError(String message) {
                            synchronized (NovaBrain.this) { thinking = false; }
                            Log.e(TAG, "AI ERROR: " + message);
                            reply("My AI core is unavailable right now. " + message);
                        }
                    });
        } catch (Exception e) {
            synchronized (this) { thinking = false; }
            Log.e(TAG, "AI REQUEST PREPARATION ERROR", e);
            reply("I couldn't prepare the AI request.");
        }
    }

    private String getEndpoint() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ENDPOINT, "");
    }

    private String getModel() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(MODEL, "gpt-4o-mini");
    }

    private String getUiSnapshot() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        return service == null
                ? "Accessibility service is not connected."
                : service.getUiSnapshot();
    }

    private void status(String text) {
        if (listener != null && text != null) listener.onStatus(text);
    }

    private void reply(String text) {
        if (text == null || text.trim().isEmpty()) return;
        memory.remember("assistant", text);
        if (listener != null) listener.onReply(text);
    }

    public synchronized boolean isBusy() { return thinking; }

    public void shutdown() {
        ai.shutdown();
    }
}
