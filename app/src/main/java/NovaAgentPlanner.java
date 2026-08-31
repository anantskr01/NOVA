package com.aircontrol;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Validates and executes bounded agent plans through registered NOVA tools. */
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
    private boolean confirmationGranted;
    private String lastToolOutput = "";

    public interface ActionExecutor {
        boolean execute(String type, String value);
        String readScreen();
        boolean clickText(String text);
        boolean clickVisibleIndex(int index);
        default String executeTool(String type, String value) { return null; }
    }

    public interface Listener {
        void status(String text);
        void reply(String text);
    }

    public NovaAgentPlanner(ActionExecutor executor, Listener listener) {
        this.executor = executor;
        this.listener = listener;
        this.registry = new NovaToolRegistry();
    }

    public String toolSummary() { return registry.promptSummary(); }
    public boolean isKnownTool(String type) { return registry.contains(type); }
    public String currentScreen() { return executor.readScreen(); }
    public boolean lastExecutionSuccessful() { return lastExecutionSuccessful; }
    public boolean lastPlanClaimedComplete() { return lastPlanClaimedComplete; }
    public String lastFailuresSummary() { return String.join(", ", lastFailures); }
    public String lastToolOutput() { return lastToolOutput; }
    public String lastStepSummary() { return String.join("\n", lastStepResults); }
    public void grantConfirmation() { confirmationGranted = true; }
    public void clearConfirmation() { confirmationGranted = false; }

    public boolean execute(String rawPlan) {
        lastFailures.clear();
        lastStepResults.clear();
        lastExecutionSuccessful = false;
        lastPlanClaimedComplete = false;
        lastToolOutput = "";

        try {
            JSONObject plan = parseObject(rawPlan);
            if (plan == null) {
                lastFailures.add("plan_parse_failure");
                return false;
            }

            String say = plan.optString("say", "").trim();
            lastPlanClaimedComplete = plan.optBoolean("complete", false);
            JSONArray actions = plan.optJSONArray("actions");

            if (actions == null) {
                if (!say.isEmpty() && lastPlanClaimedComplete) listener.reply(say);
                lastExecutionSuccessful = lastPlanClaimedComplete;
                return lastPlanClaimedComplete;
            }

            int requested = actions.length();
            int count = Math.min(requested, MAX_STEPS);
            if (requested > MAX_STEPS) {
                lastFailures.add("plan_too_large");
                listener.status("AGENT • PLAN LIMITED TO " + MAX_STEPS + " STEPS");
            }

            listener.status("AGENT • EXECUTING " + count + " STEPS");
            boolean[] succeeded = new boolean[count];

            for (int i = 0; i < count; i++) {
                JSONObject action = actions.optJSONObject(i);
                String label = "STEP " + (i + 1);

                if (action == null) {
                    lastFailures.add("invalid_action_" + (i + 1));
                    lastStepResults.add(label + " • FAILED • invalid action");
                    continue;
                }

                int required = action.optInt("requires", 0);
                if (required > 0 && (required > i || !succeeded[required - 1])) {
                    lastFailures.add("dependency_blocked_step_" + (i + 1));
                    lastStepResults.add(label + " • BLOCKED • requires STEP " + required);
                    continue;
                }

                String type = action.optString("type", "none").trim().toLowerCase(Locale.ROOT);
                String value = action.optString("value", "").trim();

                if (!registry.validate(type, value)) {
                    lastFailures.add(type.isEmpty() ? "unknown_tool" : type);
                    lastStepResults.add(label + " • FAILED • invalid tool " + type);
                    continue;
                }

                if (registry.requiresConfirmation(type) && !confirmationGranted) {
                    lastFailures.add(type + "_confirmation_required");
                    lastStepResults.add(label + " • BLOCKED • confirmation required");
                    listener.status("AGENT • CONFIRMATION REQUIRED • " + type);
                    continue;
                }

                if ("none".equals(type)) {
                    succeeded[i] = true;
                    lastStepResults.add(label + " • SKIPPED");
                    continue;
                }

                listener.status("AGENT • STEP " + (i + 1) + "/" + count + " • " + type);
                boolean ok = executeRegisteredTool(type, value);

                if (!ok && isRetryable(type) && MAX_RETRIES > 0) {
                    listener.status("AGENT • RETRY • " + type);
                    ok = executeRegisteredTool(type, value);
                }

                if (!ok) {
                    lastFailures.add(type);
                    lastStepResults.add(label + " • FAILED • " + type);
                    continue;
                }

                String expected = action.optString("expect", "").trim();
                if (!expected.isEmpty() && !verifyExpected(expected)) {
                    lastFailures.add(type + "_verification_failed");
                    lastStepResults.add(label + " • FAILED VERIFICATION • " + type);
                    listener.status("AGENT • VERIFICATION FAILED • " + type);
                } else {
                    succeeded[i] = true;
                    lastStepResults.add(label + " • VERIFIED • " + type);
                }
            }

            String screen = executor.readScreen();
            if (screen != null && !screen.trim().isEmpty()) {
                appendToolOutput("android.observe", screen.trim());
                listener.status("AGENT • VERIFIED CURRENT UI");
            }

            if (!lastFailures.isEmpty()) {
                listener.status("AGENT • " + lastFailures.size() + " ACTION(S) NOT COMPLETED");
            }

            if (!say.isEmpty() && lastFailures.isEmpty() && (count > 0 || lastPlanClaimedComplete)) {
                listener.reply(say);
            }

            lastExecutionSuccessful = lastFailures.isEmpty() && (count > 0 || lastPlanClaimedComplete);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PLAN EXECUTION ERROR", e);
            lastFailures.add("planner_error");
            return false;
        }
    }

    /** Native Android actions stay in NovaActionEngine; all other registered tools go through the tool executor. */
    private boolean executeRegisteredTool(String type, String value) {
        if (isNativeAndroidAction(type)) return executeOne(type, value);
        return executeExternalTool(type, value);
    }

    private boolean isNativeAndroidAction(String type) {
        return "home".equals(type) || "back".equals(type) || "recents".equals(type) ||
                "notifications".equals(type) || "quick_settings".equals(type) ||
                "scroll_up".equals(type) || "scroll_down".equals(type) ||
                "swipe_left".equals(type) || "swipe_right".equals(type) ||
                "open_url".equals(type) || "open_package".equals(type) ||
                "open_app".equals(type) || "settings".equals(type) ||
                "click_text".equals(type) || "click_index".equals(type) ||
                "click_coordinates".equals(type) || "long_click_text".equals(type) ||
                "read_screen".equals(type);
    }

    private boolean isRetryable(String type) {
        return !"memory.remember".equals(type) && !"memory.recall".equals(type) &&
                !"files.write".equals(type) && !"files.create".equals(type) &&
                !"code.create".equals(type) && !"code.modify".equals(type) &&
                !"communication.send_message".equals(type) && !"communication.make_call".equals(type);
    }

    private boolean verifyExpected(String expected) {
        String screen = executor.readScreen();
        if (screen == null || screen.trim().isEmpty()) return false;
        String normalizedScreen = normalize(screen);
        String normalizedExpected = normalize(expected);
        boolean found = normalizedScreen.contains(normalizedExpected);

        if (!found) {
            String[] tokens = normalizedExpected.split("\\s+");
            int significant = 0;
            int matched = 0;
            for (String token : tokens) {
                if (token.length() < 2 || isStopWord(token)) continue;
                significant++;
                if (normalizedScreen.contains(token)) matched++;
            }
            found = significant > 0 && matched >= Math.max(1, (significant + 1) / 2);
        }

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
        String l = result.toLowerCase(Locale.ROOT);
        return l.startsWith("tool error") || l.contains("confirmation required") ||
                l.contains("accessibility not connected") || l.contains("android • failed") ||
                l.contains("click failed") || l.contains("type failed") ||
                l.contains("no matches") || l.contains("http 4") || l.contains("http 5") ||
                l.contains("blocked private/local address") || l.contains("too many redirects") ||
                (l.contains("file •") && l.contains("failed"));
    }

    private void appendToolOutput(String type, String result) {
        String item = "[" + type + "]\n" + result;
        lastToolOutput = lastToolOutput.isEmpty() ? item : lastToolOutput + "\n\n" + item;
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
                String l = value.toLowerCase(Locale.ROOT);
                if (value.isEmpty() || !(l.startsWith("https://") || l.startsWith("http://"))) return false;
            }
            if (("open_app".equals(type) || "open_package".equals(type)) && value.isEmpty()) return false;
            return executor.execute(type, value);
        } catch (Exception e) {
            Log.e(TAG, "ACTION ERROR: " + type, e);
            return false;
        }
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ").replaceAll("\\s+", " ").trim();
    }

    private boolean isStopWord(String token) {
        return "the".equals(token) || "a".equals(token) || "an".equals(token) ||
                "is".equals(token) || "are".equals(token) || "on".equals(token) ||
                "in".equals(token) || "to".equals(token) || "of".equals(token) ||
                "and".equals(token) || "or".equals(token) || "my".equals(token) ||
                "your".equals(token);
    }

    private JSONObject parseObject(String raw) throws Exception {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return new JSONObject(text.substring(start, end + 1));
    }
}
