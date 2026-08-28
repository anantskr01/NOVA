package com.aircontrol;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Bounded autonomous planner for NOVA. */
public final class NovaAgentPlanner {
    private static final String TAG = "NovaAgentPlanner";
    private static final int MAX_STEPS = 8;
    private static final int MAX_RETRIES = 1;
    private static final int MAX_TOOL_OUTPUT_CHARS = 12000;
    private final ActionExecutor executor;
    private final Listener listener;
    private final NovaToolRegistry registry;
    private final List<String> lastFailures = new ArrayList<>();
    private boolean lastExecutionSuccessful;
    private String lastToolOutput = "";

    public interface ActionExecutor {
        boolean execute(String type, String value);
        String readScreen();
        boolean clickText(String text);
        boolean clickVisibleIndex(int index);
        default String executeTool(String type, String value) { return null; }
    }
    public interface Listener { void status(String text); void reply(String text); }

    public NovaAgentPlanner(ActionExecutor executor, Listener listener) {
        this.executor = executor; this.listener = listener; this.registry = new NovaToolRegistry();
    }
    public String toolSummary() { return registry.promptSummary(); }
    public boolean isKnownTool(String type) { return registry.contains(type); }
    public String currentScreen() { return executor.readScreen(); }
    public boolean lastExecutionSuccessful() { return lastExecutionSuccessful; }
    public String lastFailuresSummary() { return String.join(", ", lastFailures); }
    public String lastToolOutput() { return lastToolOutput; }

    public boolean execute(String rawPlan) {
        lastFailures.clear(); lastExecutionSuccessful = false; lastToolOutput = "";
        try {
            JSONObject plan = parseObject(rawPlan);
            if (plan == null) return false;
            String say = plan.optString("say", "").trim();
            JSONArray actions = plan.optJSONArray("actions");
            if (actions == null) {
                if (!say.isEmpty()) listener.reply(say);
                lastExecutionSuccessful = true;
                return true;
            }

            int requestedCount = actions.length();
            int count = Math.min(requestedCount, MAX_STEPS);
            if (requestedCount > MAX_STEPS) {
                lastFailures.add("plan_too_large");
                listener.status("AGENT • PLAN LIMITED TO " + MAX_STEPS + " STEPS");
            }

            listener.status("AGENT • EXECUTING " + count + " STEPS");
            for (int i = 0; i < count; i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null) {
                    lastFailures.add("invalid_action_" + (i + 1));
                    continue;
                }
                String type = action.optString("type", "none").trim().toLowerCase(Locale.ROOT);
                String value = action.optString("value", "").trim();
                if (!registry.validate(type, value)) {
                    lastFailures.add(type.isEmpty() ? "unknown" : type);
                    listener.status("AGENT • BLOCKED INVALID TOOL");
                    continue;
                }
                if (registry.requiresConfirmation(type)) {
                    lastFailures.add(type + "_confirmation_required");
                    listener.status("AGENT • CONFIRMATION REQUIRED • " + type);
                    continue;
                }
                if ("none".equals(type)) continue;

                listener.status("AGENT • STEP " + (i + 1) + "/" + count + " • " + type);
                boolean ok = type.contains(".") ? executeExternalTool(type, value) : executeOne(type, value);
                if (!ok && !type.contains(".") && MAX_RETRIES > 0) {
                    listener.status("AGENT • RETRY • " + type);
                    ok = executeOne(type, value);
                }
                if (!ok) lastFailures.add(type);
            }

            String screen = executor.readScreen();
            if (screen != null && !screen.trim().isEmpty()) listener.status("AGENT • VERIFIED CURRENT UI");
            if (!lastFailures.isEmpty()) listener.status("AGENT • " + lastFailures.size() + " ACTION(S) NOT COMPLETED");

            // Don't present a confident completion message when execution had failures.
            if (!say.isEmpty() && lastFailures.isEmpty()) listener.reply(say);
            lastExecutionSuccessful = lastFailures.isEmpty();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PLAN EXECUTION ERROR", e);
            lastFailures.add("planner_error");
            return false;
        }
    }

    private boolean executeExternalTool(String type, String value) {
        try {
            String result = executor.executeTool(type, value);
            if (result == null || result.trim().isEmpty()) return false;
            appendToolOutput(type, result.trim());
            listener.status("TOOL • RESULT RECEIVED • " + type);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "TOOL ERROR: " + type, e);
            return false;
        }
    }

    private void appendToolOutput(String type, String result) {
        String item = "[" + type + "]\n" + result;
        if (lastToolOutput.isEmpty()) lastToolOutput = item;
        else lastToolOutput += "\n\n" + item;
        if (lastToolOutput.length() > MAX_TOOL_OUTPUT_CHARS) {
            lastToolOutput = lastToolOutput.substring(lastToolOutput.length() - MAX_TOOL_OUTPUT_CHARS);
        }
    }

    private boolean executeOne(String type, String value) {
        try {
            if ("read_screen".equals(type)) {
                String text = executor.readScreen();
                listener.status(text == null ? "SCREEN • NO READABLE TEXT" : text);
                return text != null;
            }
            if ("click_text".equals(type)) return !value.isEmpty() && executor.clickText(value);
            if ("click_index".equals(type)) {
                try { return executor.clickVisibleIndex(Integer.parseInt(value)); }
                catch (NumberFormatException ignored) { return false; }
            }
            if ("open_url".equals(type)) {
                String lower = value.toLowerCase(Locale.ROOT);
                if (value.isEmpty() || !(lower.startsWith("https://") || lower.startsWith("http://"))) return false;
            }
            if (("open_app".equals(type) || "open_package".equals(type)) && value.isEmpty()) return false;
            return executor.execute(type, value);
        } catch (Exception e) {
            Log.e(TAG, "ACTION ERROR: " + type, e);
            return false;
        }
    }

    private JSONObject parseObject(String raw) throws Exception {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return new JSONObject(text.substring(start, end + 1));
    }
}
