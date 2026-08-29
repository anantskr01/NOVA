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
    private final List<String> lastStepResults = new ArrayList<>();
    private boolean lastExecutionSuccessful;
    private boolean lastPlanClaimedComplete;
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
    public boolean lastPlanClaimedComplete() { return lastPlanClaimedComplete; }
    public String lastFailuresSummary() { return String.join(", ", lastFailures); }
    public String lastToolOutput() { return lastToolOutput; }
    public String lastStepSummary() { return String.join("\n", lastStepResults); }

    public boolean execute(String rawPlan) {
        lastFailures.clear();
        lastStepResults.clear();
        lastExecutionSuccessful = false;
        lastPlanClaimedComplete = false;
        lastToolOutput = "";
        try {
            JSONObject plan = parseObject(rawPlan);
            if (plan == null) return false;
            String say = plan.optString("say", "").trim();
            lastPlanClaimedComplete = plan.optBoolean("complete", false);
            JSONArray actions = plan.optJSONArray("actions");
            if (actions == null) {
                if (!say.isEmpty() && lastPlanClaimedComplete) listener.reply(say);
                lastExecutionSuccessful = lastPlanClaimedComplete;
                return lastPlanClaimedComplete;
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
                String stepLabel = "STEP " + (i + 1);
                if (action == null) {
                    lastFailures.add("invalid_action_" + (i + 1));
                    lastStepResults.add(stepLabel + " • FAILED • invalid action");
                    continue;
                }
                String type = action.optString("type", "none").trim().toLowerCase(Locale.ROOT);
                String value = action.optString("value", "").trim();
                if (!registry.validate(type, value)) {
                    lastFailures.add(type.isEmpty() ? "unknown" : type);
                    lastStepResults.add(stepLabel + " • FAILED • invalid tool " + type);
                    listener.status("AGENT • BLOCKED INVALID TOOL");
                    continue;
                }
                if (registry.requiresConfirmation(type)) {
                    lastFailures.add(type + "_confirmation_required");
                    lastStepResults.add(stepLabel + " • BLOCKED • confirmation required");
                    listener.status("AGENT • CONFIRMATION REQUIRED • " + type);
                    continue;
                }
                if ("none".equals(type)) {
                    lastStepResults.add(stepLabel + " • SKIPPED");
                    continue;
                }

                listener.status("AGENT • STEP " + (i + 1) + "/" + count + " • " + type);
                boolean ok = type.contains(".") ? executeExternalTool(type, value) : executeOne(type, value);
                if (!ok && !type.contains(".") && MAX_RETRIES > 0) {
                    listener.status("AGENT • RETRY • " + type);
                    ok = executeOne(type, value);
                }
                if (!ok) {
                    lastFailures.add(type);
                    lastStepResults.add(stepLabel + " • FAILED • " + type);
                    continue;
                }

                String expected = action.optString("expect", "").trim();
                if (!expected.isEmpty() && !verifyExpected(expected)) {
                    lastFailures.add(type + "_verification_failed");
                    lastStepResults.add(stepLabel + " • FAILED VERIFICATION • " + type);
                    listener.status("AGENT • VERIFICATION FAILED • " + type);
                } else {
                    lastStepResults.add(stepLabel + " • VERIFIED • " + type);
                }
            }

            String screen = executor.readScreen();
            if (screen != null && !screen.trim().isEmpty()) {
                appendToolOutput("android.observe", screen.trim());
                listener.status("AGENT • VERIFIED CURRENT UI");
            }
            if (!lastFailures.isEmpty()) listener.status("AGENT • " + lastFailures.size() + " ACTION(S) NOT COMPLETED");

            if (!say.isEmpty() && lastFailures.isEmpty() && (count > 0 || lastPlanClaimedComplete)) listener.reply(say);
            lastExecutionSuccessful = lastFailures.isEmpty() && (count > 0 || lastPlanClaimedComplete);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PLAN EXECUTION ERROR", e);
            lastFailures.add("planner_error");
            return false;
        }
    }

    private boolean verifyExpected(String expected) {
        String screen = executor.readScreen();
        if (screen == null || screen.trim().isEmpty()) return false;
        String normalizedScreen = screen.toLowerCase(Locale.ROOT);
        String normalizedExpected = expected.toLowerCase(Locale.ROOT);
        boolean found = normalizedScreen.contains(normalizedExpected);
        appendToolOutput("android.verify", "EXPECTED: " + expected + "\nFOUND: " + found);
        return found;
    }

    private boolean executeExternalTool(String type, String value) {
        try {
            String result = executor.executeTool(type, value);
            if (result == null || result.trim().isEmpty()) return false;
            String clean = result.trim();
            appendToolOutput(type, clean);
            listener.status("TOOL • RESULT RECEIVED • " + type);
            if (isToolFailure(clean)) {
                listener.status("TOOL • ACTION FAILED • " + type);
                return false;
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "TOOL ERROR: " + type, e);
            return false;
        }
    }

    private boolean isToolFailure(String result) {
        String lower = result.toLowerCase(Locale.ROOT);
        return lower.startsWith("tool error")
                || lower.contains("confirmation required")
                || lower.contains("accessibility not connected")
                || lower.contains("android • failed")
                || lower.contains("click failed")
                || lower.contains("no matches")
                || lower.contains("http 4")
                || lower.contains("http 5")
                || lower.contains("blocked private/local address")
                || lower.contains("too many redirects")
                || lower.contains("file •") && lower.contains("failed");
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
