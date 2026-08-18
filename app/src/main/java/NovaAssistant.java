package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** NOVA orchestration layer: local skills first, then the optional AI planner. */
public final class NovaAssistant {
    public interface Listener { void onStatus(String text); }

    private static final String TAG = "NovaAssistant";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final String WAKE_PHRASE = "hey nova";

    private final Context context;
    private final Listener listener;
    private final SharedPreferences prefs;
    private final NovaMemory memory;
    private final NovaSecureStore secureStore;
    private final NovaAiClient ai = new NovaAiClient();
    private final NovaWebTool web = new NovaWebTool();
    private final NovaSkillRegistry skills;
    private final NovaAppCatalog apps;
    private final NovaActionEngine actions;
    private final NovaAgentPlanner agent;
    private TextToSpeech tts;

    public NovaAssistant(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        memory = new NovaMemory(this.context);
        secureStore = new NovaSecureStore(this.context);
        apps = new NovaAppCatalog(this.context);
        actions = new NovaActionEngine(this.context, new NovaActionEngine.Callback() {
            @Override public void status(String text) { status(text); }
            @Override public void reply(String text) { say(text); }
        });
        agent = new NovaAgentPlanner(new NovaAgentPlanner.ActionExecutor() {
            @Override public boolean execute(String type, String value) { return actions.execute(type, value); }
            @Override public String readScreen() { return NovaAssistant.this.readScreen(); }
            @Override public boolean clickText(String text) { return NovaAssistant.this.clickText(text); }
            @Override public boolean clickVisibleIndex(int index) {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service != null && service.clickVisibleIndex(index);
            }
        }, new NovaAgentPlanner.Listener() {
            @Override public void status(String text) { status(text); }
            @Override public void reply(String text) { say(text); }
        });
        skills = new NovaSkillRegistry(this.context, new NovaSkillRegistry.Callback() {
            @Override public void reply(String text) { say(text); }
            @Override public void status(String text) { status(text); }
        });
        tts = new TextToSpeech(this.context, result -> {
            if (result == TextToSpeech.SUCCESS) {
                try { tts.setLanguage(Locale.getDefault()); } catch (Exception ignored) { }
            }
        });
    }

    public void saveAiSettings(String endpoint, String apiKey, String model) {
        prefs.edit()
                .putString(ENDPOINT, endpoint == null ? "" : endpoint.trim())
                .putString(MODEL, model == null || model.trim().isEmpty() ? "gpt-4o-mini" : model.trim())
                .apply();
        secureStore.putApiKey(apiKey == null ? "" : apiKey.trim());
        status("AI CORE CONFIGURED • KEY PROTECTED");
    }

    public String getEndpoint() { return prefs.getString(ENDPOINT, ""); }
    public String getModel() { return prefs.getString(MODEL, "gpt-4o-mini"); }
    public boolean hasAiCore() { return !getEndpoint().isEmpty(); }

    public void handleVoice(String raw) {
        if (raw == null) return;
        String text = raw.trim();
        String lower = text.toLowerCase(Locale.ROOT);
        int wake = lower.indexOf(WAKE_PHRASE);
        if (wake >= 0) {
            text = text.substring(wake + WAKE_PHRASE.length()).trim();
            if (text.isEmpty()) { say("Yes. I am listening."); return; }
        } else if (lower.equals("nova")) {
            say("Yes. I am listening.");
            return;
        }
        handle(text);
    }

    public void handle(String raw) {
        if (raw == null) return;
        String command = raw.trim();
        if (command.isEmpty()) return;
        memory.remember("user", command);
        String c = command.toLowerCase(Locale.ROOT);
        status("PROCESSING • " + command);

        try {
            if (skills.handle(command)) return;
            if (handleMemory(c, command)) return;

            if (containsAny(c, "stop nova", "stop listening", "be quiet", "stop speaking")) {
                if (tts != null) tts.stop();
                say("Okay.");
                return;
            }
            if (containsAny(c, "go home", "home screen", "take me home")) { actions.execute("home", ""); say("Going home."); return; }
            if (containsAny(c, "go back", "back")) { actions.execute("back", ""); say("Going back."); return; }
            if (containsAny(c, "recent apps", "open recents", "show recents")) { actions.execute("recents", ""); say("Opening recent apps."); return; }
            if (containsAny(c, "notifications", "show notifications")) { actions.execute("notifications", ""); say("Opening notifications."); return; }
            if (containsAny(c, "quick settings", "open quick settings")) { actions.execute("quick_settings", ""); say("Opening quick settings."); return; }
            if (containsAny(c, "scroll up", "swipe up")) { actions.execute("scroll_up", ""); say("Scrolling up."); return; }
            if (containsAny(c, "scroll down", "swipe down")) { actions.execute("scroll_down", ""); say("Scrolling down."); return; }
            if (containsAny(c, "swipe left", "go left")) { actions.execute("swipe_left", ""); say("Moving left."); return; }
            if (containsAny(c, "swipe right", "go right")) { actions.execute("swipe_right", ""); say("Moving right."); return; }
            if (containsAny(c, "read screen", "what is on screen", "describe screen", "what can you see")) { say(readScreen()); return; }
            if (containsAny(c, "show ui snapshot", "inspect screen", "understand screen")) { say(getUiSnapshot()); return; }
            if (containsAny(c, "recent notifications", "read notifications", "what notifications do i have")) { say(NovaNotificationListenerService.snapshot()); return; }
            if (containsAny(c, "open settings", "settings")) { actions.execute("settings", ""); say("Opening settings."); return; }
            if (containsAny(c, "open accessibility settings", "accessibility settings")) { launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); say("Opening accessibility settings."); return; }
            if (containsAny(c, "open notification access", "notification access")) { launch(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); say("Opening notification access."); return; }
            if (containsAny(c, "list apps", "show my apps", "what apps do i have")) { say(apps.launchableSummary(35)); return; }
            if (c.startsWith("search for ") || c.startsWith("search ") || c.startsWith("google ")) {
                String q = command.replaceFirst("(?i)^(search for|search|google)\\s+", "").trim();
                if (!q.isEmpty()) { search(q); return; }
            }
            java.util.regex.Matcher numbered = java.util.regex.Pattern.compile("(?i)(?:tap|click|open)\\s+(?:the\\s+)?(\\d+)(?:st|nd|rd|th)?(?:\\s+(?:result|item|option))?").matcher(command);
            if (numbered.matches()) {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                int index = Integer.parseInt(numbered.group(1));
                boolean ok = service != null && service.clickVisibleIndex(index);
                say(ok ? "Done." : "I couldn't activate that visible item.");
                return;
            }
            if (c.startsWith("open ")) { openByName(command.substring(5).trim()); return; }
            if (c.startsWith("do ") && c.contains(" then ")) { runSequence(command.substring(3)); return; }

            if (!hasAiCore()) {
                say("I can do built-in tablet tasks now. Configure an OpenAI-compatible AI endpoint for open-ended reasoning and multi-step planning.");
                return;
            }
            askAi(command);
        } catch (Exception e) {
            Log.e(TAG, "COMMAND ERROR", e);
            say("I couldn't complete that action. Check that the required Android permission is enabled.");
        }
    }

    private boolean handleMemory(String c, String original) {
        if (c.equals("forget everything") || c.equals("clear memory") || c.equals("delete my memory")) {
            memory.clear();
            context.getSharedPreferences("nova_user_memory", Context.MODE_PRIVATE).edit().clear().apply();
            say("Local NOVA memory has been cleared.");
            return true;
        }
        if (c.startsWith("remember ")) {
            String note = original.substring(9).trim();
            if (!note.isEmpty()) {
                int as = note.toLowerCase(Locale.ROOT).indexOf(" as ");
                if (as > 0 && as + 4 < note.length()) {
                    String value = note.substring(0, as).trim();
                    String key = note.substring(as + 4).trim();
                    memory.rememberFact(key, value);
                    say("I'll remember that locally on this tablet.");
                } else {
                    context.getSharedPreferences("nova_user_memory", Context.MODE_PRIVATE).edit().putString("note", note).apply();
                    memory.rememberFact("note", note);
                    say("I'll remember that locally on this tablet.");
                }
                return true;
            }
        }
        if (containsAny(c, "what do you remember", "what do you know about me")) {
            String note = memory.factsSummary();
            if (note.equals("No saved facts.")) {
                note = context.getSharedPreferences("nova_user_memory", Context.MODE_PRIVATE).getString("note", note);
            }
            say(note);
            return true;
        }
        return false;
    }

    private void runSequence(String sequence) {
        String[] steps = sequence.split("(?i)\\s+then\\s+");
        status("MULTI-ACTION PLAN • " + steps.length + " STEPS");
        for (String step : steps) {
            if (!step.trim().isEmpty()) handle(step.trim());
        }
    }

    private void openByName(String name) {
        if (name.isEmpty()) return;
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("youtube")) {
            actions.execute("open_app", "YouTube");
            return;
        }
        android.content.pm.ResolveInfo info = apps.resolve(name);
        Intent launchIntent = apps.launchIntent(info);
        if (launchIntent != null) {
            launch(launchIntent);
            say("Opening " + info.loadLabel(context.getPackageManager()) + ".");
            return;
        }
        say("I couldn't find an installed app called " + name + ".");
    }

    private void search(String query) {
        status("WEB RESEARCH • " + query);
        web.search(query, new NovaWebTool.Callback() {
            @Override public void onResult(String text) { say(text); }
            @Override public void onError(String error) {
                launch(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))));
                say("I opened the search results.");
            }
        });
    }

    private void askAi(String command) {
        status("AI CORE • PLANNING");
        JSONArray messages = new JSONArray();
        try {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content",
                    "You are NOVA, a careful Android tablet agent. Plan the user's goal as a bounded sequence of concrete actions. " +
                    "Return JSON only: {\"say\":\"short response\",\"actions\":[{\"type\":\"home|back|recents|notifications|quick_settings|scroll_up|scroll_down|swipe_left|swipe_right|open_url|open_package|open_app|click_text|click_index|search|read_screen|settings|none\",\"value\":\"optional value\"}]}. " +
                    "Use at most 8 actions. Use open_app with a human app name. Use click_text only for visible UI text. Use click_index only after read_screen when the user explicitly refers to a numbered visible result. " +
                    "For UI tasks, prefer read_screen before click_text when the target is ambiguous. Never bypass permissions, security, authentication, or private app data. " +
                    "Never claim an action succeeded unless NOVA can dispatch it. Prefer reversible actions. " +
                    "If the task is impossible with available Android APIs, explain the limitation in say and return no risky actions.");
            messages.put(system);

            JSONObject contextMessage = new JSONObject();
            contextMessage.put("role", "system");
            contextMessage.put("content", "Saved NOVA memory:\n" + memory.factsSummary() +
                    "\n\nCurrent accessibility UI snapshot:\n" + getUiSnapshot());
            messages.put(contextMessage);

            JSONArray history = memory.recent();
            for (int i = 0; i < history.length(); i++) messages.put(history.getJSONObject(i));
            ai.chat(getEndpoint(), secureStore.getApiKey(), getModel(), messages, new NovaAiClient.Callback() {
                @Override public void onResult(String text) {
                    memory.remember("assistant", text);
                    if (!agent.execute(text)) say(text);
                }
                @Override public void onError(String message) {
                    say("My AI core is unavailable right now. " + message);
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "AI REQUEST PREP ERROR", e);
            say("AI request could not be prepared.");
        }
    }

    private boolean executeAiPlan(String raw) {
        try {
            String jsonText = raw.trim();
            if (jsonText.startsWith("```")) {
                jsonText = jsonText.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
            }
            JSONObject plan = new JSONObject(jsonText);
            String response = plan.optString("say", "");
            if (!response.isEmpty()) status(response);
            JSONArray actionsArray = plan.optJSONArray("actions");
            if (actionsArray != null) {
                int count = Math.min(actionsArray.length(), 8);
                for (int i = 0; i < count; i++) {
                    JSONObject action = actionsArray.optJSONObject(i);
                    if (action == null) continue;
                    String type = action.optString("type", "none");
                    String value = action.optString("value", "");
                    if ("read_screen".equals(type)) {
                        status(readScreen());
                        continue;
                    }
                    if ("click_text".equals(type)) {
                        boolean ok = clickText(value);
                        status(ok ? "UI ACTION • COMPLETED" : "UI ACTION • NOT FOUND");
                        continue;
                    }
                    boolean ok = actions.execute(type, value);
                    if (!ok && !"none".equals(type)) status("ACTION BLOCKED • CHECK PERMISSIONS");
                }
            }
            if (!response.isEmpty()) say(response);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String getUiSnapshot() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) return "Accessibility access is not connected.";
        return service.getUiSnapshot();
    }

    private String readScreen() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) return "Accessibility access is not connected, so I cannot inspect the current screen.";
        return service.getVisibleTextSummary();
    }

    private boolean clickText(String text) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        return service != null && service.clickText(text);
    }

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private boolean containsAny(String value, String... options) {
        for (String option : options) if (value.equals(option) || value.contains(option)) return true;
        return false;
    }

    private void status(String text) {
        if (listener != null) listener.onStatus(text);
    }

    private void say(String text) {
        if (text == null || text.trim().isEmpty()) return;
        status(text);
        if (tts != null) {
            try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NOVA"); } catch (Exception ignored) { }
        }
    }

    public void destroy() {
        if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { } tts = null; }
        ai.shutdown();
        web.shutdown();
    }
}
