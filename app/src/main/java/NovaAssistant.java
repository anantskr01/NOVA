package com.aircontrol;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import java.util.Locale;

/** NOVA interaction gateway: deterministic local skills first, then the central AI Brain. */
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
    private final NovaWebTool web = new NovaWebTool();
    private final NovaSkillRegistry skills;
    private final NovaAppCatalog apps;
    private final NovaActionEngine actions;
    private final NovaBrain brain;
    private final NovaTaskManager taskManager;
    private TextToSpeech tts;

    public NovaAssistant(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        memory = new NovaMemory(this.context);
        secureStore = new NovaSecureStore(this.context);
        apps = new NovaAppCatalog(this.context);
        actions = new NovaActionEngine(this.context, new NovaActionEngine.Callback() {
            @Override public void status(String text) { NovaAssistant.this.status(text); }
            @Override public void reply(String text) { NovaAssistant.this.say(text); }
        });
        brain = new NovaBrain(this.context, actions, memory, new NovaBrain.Listener() {
            @Override public void onStatus(String text) { NovaAssistant.this.status(text); }
            @Override public void onReply(String text) { NovaAssistant.this.say(text); }
        });
        taskManager = new NovaTaskManager(this.context, brain);
        skills = new NovaSkillRegistry(this.context, new NovaSkillRegistry.Callback() {
            @Override public void reply(String text) { NovaAssistant.this.say(text); }
            @Override public void status(String text) { NovaAssistant.this.status(text); }
        });
        tts = new TextToSpeech(this.context, result -> {
            if (result == TextToSpeech.SUCCESS) {
                try { tts.setLanguage(Locale.getDefault()); } catch (Exception ignored) { }
            }
        });
    }

    public void saveAiSettings(String endpoint, String apiKey, String model) {
        prefs.edit().putString(ENDPOINT, endpoint == null ? "" : endpoint.trim())
                .putString(MODEL, model == null || model.trim().isEmpty() ? "gpt-4o-mini" : model.trim()).apply();
        secureStore.putApiKey(apiKey == null ? "" : apiKey.trim());
        status("AI CORE CONFIGURED • KEY PROTECTED");
    }

    public String getEndpoint() { return prefs.getString(ENDPOINT, ""); }
    public String getModel() { return prefs.getString(MODEL, "gpt-4o-mini"); }
    public boolean hasAiCore() { return !getEndpoint().trim().isEmpty(); }

    public void handleVoice(String raw) {
        if (raw == null) return;
        String text = NovaInputNormalizer.normalize(raw);
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
        String command = NovaInputNormalizer.normalize(raw);
        if (command.isEmpty()) return;
        String c = command.toLowerCase(Locale.ROOT);
        status("PROCESSING • " + command);

        try {
            if (containsAny(c, "stop nova", "stop listening", "be quiet", "stop speaking")) {
                if (tts != null) tts.stop();
                taskManager.cancelAll();
                say("Okay. I stopped NOVA's active and queued tasks.");
                return;
            }
            if (containsAny(c, "task status", "show tasks", "list tasks", "what tasks are running", "task list")) {
                say(taskManager.statusText()); return;
            }
            if (containsAny(c, "active task", "what are you doing", "what task are you running")) {
                say(taskManager.activeText()); return;
            }
            if (containsAny(c, "cancel queued tasks", "cancel queued goals")) {
                int count = taskManager.cancelQueued();
                say(count == 0 ? "There are no queued tasks to cancel." : "Cancelled " + count + " queued task" + (count == 1 ? "." : "s."));
                return;
            }
            if (c.equals("cancel tasks") || c.equals("cancel all tasks") || c.equals("cancel all")) {
                taskManager.cancelAll(); say("All NOVA tasks have been cancelled."); return;
            }

            java.util.regex.Matcher cancelTask = java.util.regex.Pattern.compile(
                    "(?i)^cancel\\s+(?:task\\s+)?(?:nova[- ]?t\\s*[- ]?(\\d{1,6})|nova[- ]?t(\\d{1,6}))\\s*$"
            ).matcher(command);
            if (cancelTask.matches()) {
                String digits = cancelTask.group(1) != null ? cancelTask.group(1) : cancelTask.group(2);
                String id = "NOVA-T" + String.format(Locale.US, "%04d", Integer.parseInt(digits));
                boolean cancelled = taskManager.cancel(id);
                say(cancelled ? "Cancelled " + id + "." : "I couldn't cancel " + id + ". It may already be finished or no longer exists.");
                return;
            }

            if (skills.handle(command)) return;
            if (handleMemory(c, command)) return;

            if (containsAny(c, "go home", "home screen", "take me home")) { executeLocal("home", "Going home."); return; }
            if (containsAny(c, "go back", "back")) { executeLocal("back", "Going back."); return; }
            if (containsAny(c, "recent apps", "open recents", "show recents")) { executeLocal("recents", "Opening recent apps."); return; }
            if (containsAny(c, "notifications", "show notifications")) { executeLocal("notifications", "Opening notifications."); return; }
            if (containsAny(c, "quick settings", "open quick settings")) { executeLocal("quick_settings", "Opening quick settings."); return; }
            if (containsAny(c, "scroll up", "swipe up")) { executeLocal("scroll_up", "Scrolling up."); return; }
            if (containsAny(c, "scroll down", "swipe down")) { executeLocal("scroll_down", "Scrolling down."); return; }
            if (containsAny(c, "swipe left", "go left")) { executeLocal("swipe_left", "Moving left."); return; }
            if (containsAny(c, "swipe right", "go right")) { executeLocal("swipe_right", "Moving right."); return; }
            if (containsAny(c, "read screen", "what is on screen", "describe screen", "what can you see")) { say(readScreen()); return; }
            if (containsAny(c, "show ui snapshot", "inspect screen", "understand screen")) { say(getUiSnapshot()); return; }
            if (containsAny(c, "recent notifications", "read notifications", "what notifications do i have")) { say(NovaNotificationListenerService.snapshot()); return; }
            if (containsAny(c, "open settings", "settings")) { executeLocal("settings", "Opening settings."); return; }
            if (containsAny(c, "open accessibility settings", "accessibility settings")) { launch(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); say("Opening accessibility settings."); return; }
            if (containsAny(c, "open notification access", "notification access")) { launch(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); say("Opening notification access settings."); return; }
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
                say(ok ? "Done." : "I couldn't activate that visible item."); return;
            }

            if (c.startsWith("open ")) if (openByName(command.substring(5).trim())) return;

            if (hasAiCore()) {
                int priority = parsePriority(c);
                String goal = stripPriorityPrefix(command, c);
                String id = taskManager.submit(goal, priority);
                if (id.isEmpty()) say("I couldn't add that task to NOVA's task manager.");
                else status("TASK " + id + " • ACCEPTED • P" + priority);
                return;
            }
            say("I can do built-in tablet tasks now. Configure an AI endpoint for open-ended reasoning and multi-step planning.");
        } catch (Exception e) {
            Log.e(TAG, "COMMAND ERROR", e);
            say("I couldn't complete that action. Check that the required Android permission is enabled.");
        }
    }

    private int parsePriority(String c) {
        if (c.startsWith("urgent ") || c.startsWith("critical ") || c.startsWith("high priority ")) return NovaTaskManager.PRIORITY_HIGH;
        if (c.startsWith("low priority ") || c.startsWith("when you can ") || c.startsWith("later ")) return NovaTaskManager.PRIORITY_LOW;
        return NovaTaskManager.PRIORITY_NORMAL;
    }
    private String stripPriorityPrefix(String original, String lower) {
        String[] prefixes = {"urgent ", "critical ", "high priority ", "low priority ", "when you can ", "later "};
        for (String prefix : prefixes) if (lower.startsWith(prefix)) return original.substring(prefix.length()).trim();
        return original.trim();
    }
    private void executeLocal(String type, String success) {
        boolean ok = actions.execute(type, "");
        say(ok ? success : "I couldn't perform that action. Check the required Android permission.");
    }
    private boolean handleMemory(String c, String original) {
        if (c.equals("forget everything") || c.equals("clear memory") || c.equals("delete my memory")) {
            memory.clear(); context.getSharedPreferences("nova_user_memory", Context.MODE_PRIVATE).edit().clear().apply(); taskManager.cancelAll(); say("Local NOVA memory has been cleared."); return true;
        }
        if (c.startsWith("remember ")) {
            String note = original.substring(9).trim();
            if (!note.isEmpty()) {
                int as = note.toLowerCase(Locale.ROOT).indexOf(" as ");
                if (as > 0 && as + 4 < note.length()) memory.rememberFact(note.substring(as + 4).trim(), note.substring(0, as).trim());
                else { context.getSharedPreferences("nova_user_memory", Context.MODE_PRIVATE).edit().putString("note", note).apply(); memory.rememberFact("note", note); }
                say("I'll remember that locally on this tablet."); return true;
            }
        }
        if (containsAny(c, "what do you remember", "what do you know about me")) {
            String note = memory.factsSummary();
            if (note.equals("No saved facts.")) note = context.getSharedPreferences("nova_user_memory", Context.MODE_PRIVATE).getString("note", note);
            say(note); return true;
        }
        return false;
    }
    private boolean openByName(String name) {
        if (name.isEmpty()) return false;
        if (name.toLowerCase(Locale.ROOT).contains("youtube")) return actions.execute("open_app", "YouTube");
        android.content.pm.ResolveInfo info = apps.resolve(name); Intent launchIntent = apps.launchIntent(info);
        if (launchIntent != null) { launch(launchIntent); say("Opening " + info.loadLabel(context.getPackageManager()) + "."); return true; }
        return false;
    }
    private void search(String query) {
        status("WEB RESEARCH • " + query);
        web.search(query, new NovaWebTool.Callback() {
            @Override public void onResult(String text) { say(text); }
            @Override public void onError(String error) { if (!actions.execute("search", query)) { say("I couldn't open the search results."); return; } say("I opened the search results."); }
        });
    }
    private String getUiSnapshot() { GestureAccessibilityService service = GestureAccessibilityService.getInstance(); return service == null ? "Accessibility access is not connected." : service.getUiSnapshot(); }
    private String readScreen() { GestureAccessibilityService service = GestureAccessibilityService.getInstance(); return service == null ? "Accessibility access is not connected, so I cannot inspect the current screen." : service.getVisibleTextSummary(); }
    private void launch(Intent intent) { intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); context.startActivity(intent); }
    private boolean containsAny(String value, String... options) { for (String option : options) if (value.equals(option) || value.contains(option)) return true; return false; }
    private void status(String text) { if (listener != null && text != null) listener.onStatus(text); }
    private void say(String text) { if (text == null || text.trim().isEmpty()) return; if (listener != null) listener.onStatus(text); if (tts != null) try { tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "NOVA"); } catch (Exception ignored) { } }
    public void destroy() { if (tts != null) { try { tts.stop(); tts.shutdown(); } catch (Exception ignored) { } tts = null; } taskManager.shutdown(); brain.shutdown(); web.shutdown(); }
}
