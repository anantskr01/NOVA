package com.aircontrol;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Central orchestration layer for local actions, conversation and bounded autonomous planning. */
public final class NovaBrain {
    private static final String TAG = "NovaBrain";
    private final NovaMemory memory;
    private final NovaActionEngine actions;
    private final NovaAgentPlanner planner;
    private final NovaAiClient ai;
    private final NovaToolExecutor toolExecutor;
    private final NovaAgentLoop agentLoop;
    private final NovaTaskStore taskStore;
    private final Listener listener;

    public interface Listener { void status(String text); void reply(String text); }

    public NovaBrain(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        this.listener = listener;
        memory = new NovaMemory(app);
        taskStore = new NovaTaskStore(app);
        ai = new NovaAiClient();
        toolExecutor = new NovaToolExecutor(app);
        actions = new NovaActionEngine(app, new NovaActionEngine.Callback() {
            @Override public void status(String text) { NovaBrain.this.status(text); }
            @Override public void reply(String text) { NovaBrain.this.reply(text); }
        });
        planner = new NovaAgentPlanner(new NovaAgentPlanner.ActionExecutor() {
            @Override public boolean execute(String type, String value) { return actions.execute(type, value); }
            @Override public String readScreen() { return readCurrentScreen(); }
            @Override public boolean clickText(String text) { return clickVisibleText(text); }
            @Override public boolean clickVisibleIndex(int index) {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service != null && service.clickVisibleIndex(index);
            }
            @Override public String executeTool(String type, String value) { return toolExecutor.execute(type, value); }
        }, new NovaAgentPlanner.Listener() {
            @Override public void status(String text) { NovaBrain.this.status(text); }
            @Override public void reply(String text) { NovaBrain.this.reply(text); }
        });
        agentLoop = new NovaAgentLoop(ai, planner, memory, taskStore, new NovaAgentLoop.Callback() {
            @Override public void status(String text) { NovaBrain.this.status(text); }
            @Override public void reply(String text) { NovaBrain.this.reply(text); }
        });
    }

    public NovaBrain(Context context) {
        this(context, new Listener() {
            @Override public void status(String text) { Log.d(TAG, "STATUS: " + text); }
            @Override public void reply(String text) { Log.d(TAG, "REPLY: " + text); }
        });
    }

    public boolean handle(String command, String endpoint, String apiKey, String model) {
        if (command == null || command.trim().isEmpty()) return false;
        String text = command.trim();
        memory.remember("user", text);
        status("BRAIN • UNDERSTANDING");

        // Deterministic built-in commands always win.
        if (handleLocalCommand(text)) return true;

        if (endpoint == null || endpoint.trim().isEmpty()) {
            reply("My AI core is not configured yet.");
            return false;
        }

        // IMPORTANT: ordinary conversation must not enter the autonomous agent loop.
        // The planner expects action JSON, so sending a simple question such as
        // "what's happening?" through it can produce parse/retry failures and the
        // misleading "could not be completed reliably" message.
        if (!needsAutonomousAgent(text)) {
            chatDirectly(text, endpoint, apiKey, model);
            return true;
        }

        think(text, endpoint, apiKey, model);
        return true;
    }

    private void chatDirectly(String command, String endpoint, String apiKey, String model) {
        status("NOVA • THINKING");
        try {
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "You are NOVA, a helpful voice assistant running on an Android tablet. " +
                    "Answer naturally and briefly. Do not return JSON. Do not invent actions or claim to control the device. " +
                    "If the user asks a normal question, simply answer it conversationally.");
            messages.put(system);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", command);
            messages.put(user);

            ai.chat(endpoint, apiKey, model, messages, new NovaAiClient.Callback() {
                @Override public void onResult(String text) {
                    memory.remember("assistant", text);
                    status("NOVA • READY");
                    reply(text);
                }

                @Override public void onError(String message) {
                    Log.e(TAG, "DIRECT CHAT ERROR: " + message);
                    status("NOVA • AI ERROR");
                    reply("I'm having trouble reaching my AI brain right now.");
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "DIRECT CHAT SETUP ERROR", e);
            reply("I'm having trouble starting my AI brain right now.");
        }
    }

    /** Only commands that plausibly require device/tool actions go to the autonomous planner. */
    private boolean needsAutonomousAgent(String command) {
        String c = command.toLowerCase(Locale.ROOT).trim();
        String[] actionStarts = {
                "open ", "launch ", "start ", "send ", "message ", "text ", "call ",
                "tap ", "click ", "press ", "type ", "write ", "enter ", "play ",
                "pause ", "stop ", "turn on", "turn off", "enable ", "disable ",
                "set ", "change ", "switch ", "navigate ", "search ", "find ",
                "remind ", "remember to ", "schedule ", "create ", "delete ",
                "download ", "upload ", "share ", "post ", "reply ", "email "
        };
        for (String start : actionStarts) if (c.startsWith(start)) return true;
        return c.contains(" on whatsapp") || c.contains(" on instagram") ||
                c.contains(" on youtube") || c.contains(" on chrome") ||
                c.contains("on my screen") || c.contains("in the app");
    }

    private boolean handleLocalCommand(String command) {
        String c = command.toLowerCase(Locale.ROOT);
        try {
            if (containsAny(c, "go home", "home screen", "take me home")) return actions.execute("home", "");
            if (containsAny(c, "go back", "press back", "back")) return actions.execute("back", "");
            if (containsAny(c, "recent apps", "open recents", "show recents")) return actions.execute("recents", "");
            if (containsAny(c, "show notifications", "open notifications", "notifications")) return actions.execute("notifications", "");
            if (containsAny(c, "quick settings", "open quick settings")) return actions.execute("quick_settings", "");
            if (containsAny(c, "scroll up", "swipe up")) return actions.execute("scroll_up", "");
            if (containsAny(c, "scroll down", "swipe down")) return actions.execute("scroll_down", "");
            if (containsAny(c, "swipe left", "go left")) return actions.execute("swipe_left", "");
            if (containsAny(c, "swipe right", "go right")) return actions.execute("swipe_right", "");
            if (containsAny(c, "open settings", "settings")) return actions.execute("settings", "");
            if (containsAny(c, "read screen", "what is on screen", "describe screen", "what can you see")) {
                String screen = readCurrentScreen();
                reply(screen == null || screen.trim().isEmpty() ? "I cannot read the current screen." : screen);
                return true;
            }
            if (c.startsWith("remember ")) {
                String note = command.substring("remember ".length()).trim();
                if (!note.isEmpty()) { memory.rememberFact("note", note); reply("I'll remember that locally."); return true; }
            }
            if (containsAny(c, "what do you remember", "what do you know about me")) { reply(memory.factsSummary()); return true; }
            if (c.startsWith("open ")) {
                String appName = command.substring(5).trim();
                if (!appName.isEmpty()) {
                    boolean opened = actions.execute("open_app", appName);
                    if (opened) { reply("Done — " + appName + " is open."); return true; }
                }
            }
        } catch (Exception e) { Log.e(TAG, "LOCAL COMMAND ERROR", e); }
        return false;
    }

    public void think(String command, String endpoint, String apiKey, String model) {
        if (agentLoop.isRunning()) { reply("I'm still processing the previous request."); return; }
        status("BRAIN • AGENT STARTING");
        agentLoop.start(command, endpoint, apiKey, model);
    }

    public boolean resumeTask() { return agentLoop.resume(); }
    public boolean confirmPendingTask() { return agentLoop.confirmAndResume(); }
    public void declinePendingTask() { agentLoop.cancelPendingConfirmation(); }
    public NovaTaskStore.State taskState() { return taskStore.state(); }
    public boolean hasPendingConfirmation() { return taskStore.hasPendingConfirmation(); }
    public String pendingConfirmationAction() { return taskStore.pendingAction(); }

    private String readCurrentScreen() {
        try {
            GestureAccessibilityService service = GestureAccessibilityService.getInstance();
            if (service == null) return "Accessibility service is not connected.";
            String snapshot = service.getUiSnapshot();
            if (snapshot == null || snapshot.trim().isEmpty()) return service.getVisibleTextSummary();
            return snapshot;
        } catch (Exception e) { Log.e(TAG, "SCREEN READ ERROR", e); return "Unable to read current screen."; }
    }
    private boolean clickVisibleText(String text) {
        try {
            GestureAccessibilityService service = GestureAccessibilityService.getInstance();
            return service != null && service.clickText(text);
        } catch (Exception e) { Log.e(TAG, "CLICK ERROR", e); return false; }
    }
    private boolean containsAny(String value, String... options) {
        if (value == null) return false;
        for (String option : options) if (value.equals(option) || value.contains(option)) return true;
        return false;
    }
    private void status(String text) { if (listener != null && text != null && !text.trim().isEmpty()) listener.status(text); }
    private void reply(String text) { if (listener != null && text != null && !text.trim().isEmpty()) listener.reply(text); }
    public void destroy() { try { agentLoop.stop(); } catch (Exception ignored) {} try { ai.shutdown(); } catch (Exception e) { Log.e(TAG, "AI SHUTDOWN ERROR", e); } }
}
