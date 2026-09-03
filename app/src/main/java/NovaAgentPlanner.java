package com.aircontrol;

import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded autonomous planner: observe -> act -> verify -> re-observe -> next action. */
public final class NovaAgentPlanner {
    private static final String TAG = "NovaAgentPlanner";
    private static final long VERIFY_DELAY_MS = 450L;
    private static final long RETRY_DELAY_MS = 350L;
    private static final int MAX_SCREEN_CHARS = 5000;

    public static final class ExecutionResult {
        public final boolean planValid;
        public final boolean completed;
        public final int failedSteps;
        public final String failedAction;
        public final String finalScreen;
        public final String say;
        public final String toolResults;

        ExecutionResult(boolean v, boolean c, int f, String a, String s, String y, String t) {
            planValid = v;
            completed = c;
            failedSteps = f;
            failedAction = a == null ? "" : a;
            finalScreen = s == null ? "" : s;
            say = y == null ? "" : y;
            toolResults = t == null ? "" : t;
        }
    }

    private static final Set<String> ALLOWED = new HashSet<>();
    static {
        String[] values = {
                "home", "back", "recents", "notifications", "quick_settings",
                "scroll_up", "scroll_down", "swipe_left", "swipe_right",
                "open_url", "open_package", "open_app", "click_text", "click_index",
                "type_text", "press_enter", "search", "read_screen", "screen_observe",
                "web_search", "web_fetch", "web_research", "memory_search", "remember",
                "parallel", "settings", "wait", "none"
        };
        for (String value : values) ALLOWED.add(value);
    }

    public interface ActionExecutor {
        boolean execute(String type, String value);
        String readScreen();
        boolean clickText(String text);
        boolean clickVisibleIndex(int index);
        default String executeTool(String type, String value) {
            return execute(type, value) ? "{\"ok\":true}" : "{\"ok\":false}";
        }
        default String executeParallel(String value) {
            return "{\"ok\":false,\"error\":\"parallel_not_supported\"}";
        }
    }

    public interface Listener {
        void status(String text);
        void reply(String text);
    }

    private final ActionExecutor executor;
    private final Listener listener;
    private final NovaToolRegistry tools;

    public NovaAgentPlanner(ActionExecutor e, Listener l) {
        this(e, l, new NovaToolRegistry());
    }

    public NovaAgentPlanner(ActionExecutor e, Listener l, NovaToolRegistry t) {
        executor = e;
        listener = l;
        tools = t == null ? new NovaToolRegistry() : t;
    }

    public boolean execute(String rawPlan) {
        return executeDetailed(rawPlan).completed;
    }

    public ExecutionResult executeDetailed(String rawPlan) {
        long started = System.currentTimeMillis();
        try {
            JSONObject plan = parseObject(rawPlan);
            if (plan == null) {
                listener.status("AGENT • INVALID PLAN");
                return result(false, false, 1, "invalid_plan", "", "", "");
            }

            String say = plan.optString("say", "").trim();
            JSONArray actions = plan.optJSONArray("actions");
            if (actions == null || actions.length() == 0) {
                if (!say.isEmpty()) listener.reply(say);
                return result(true, true, 0, "", screen(), say, "");
            }
            if (actions.length() > NovaAgentPolicy.MAX_STEPS) {
                listener.status("AGENT • PLAN REJECTED • STEP LIMIT");
                return result(false, false, 1, "step_limit_exceeded", screen(), "", "");
            }

            listener.status("AGENT • OBSERVE → PLAN → ACT → VERIFY");
            List<String> failures = new ArrayList<>();
            JSONArray outputs = new JSONArray();
            String previous = screen();

            for (int i = 0; i < actions.length(); i++) {
                if (NovaAgentPolicy.taskExpired(started)) {
                    failures.add("task_timeout");
                    listener.status("AGENT • SAFE STOP • TASK TIMEOUT");
                    break;
                }

                JSONObject action = actions.optJSONObject(i);
                if (action == null) {
                    failures.add("malformed_step_" + (i + 1));
                    continue;
                }

                String type = action.optString("type", "none").trim().toLowerCase();
                String value = NovaAgentPolicy.bounded(action.optString("value", "").trim(), 2048);
                if (!tools.contains(type) || !ALLOWED.contains(type)) {
                    listener.status("AGENT • BLOCKED UNKNOWN ACTION • " + type);
                    failures.add(type);
                    break;
                }
                if ("none".equals(type)) continue;

                listener.status("AGENT • STEP " + (i + 1) + "/" + actions.length() + " • ACT • " + type);
                String output = executeOne(type, value);
                boolean ok = outputOk(output);
                if (!ok && shouldRetry(type)) {
                    SystemClock.sleep(RETRY_DELAY_MS);
                    listener.status("AGENT • RETRY • " + type);
                    output = executeOne(type, value);
                    ok = outputOk(output);
                }
                addResult(outputs, i, type, value, output);

                if (!ok) {
                    failures.add(type);
                    listener.status("AGENT • RECOVERY NEEDED • " + type);
                    break;
                }

                if (isInformational(type) || "parallel".equals(type)) {
                    // Informational calls may be grouped because they do not mutate Android UI state.
                    continue;
                }

                if (needsVerification(type)) {
                    SystemClock.sleep(VERIFY_DELAY_MS);
                    String after = screen();
                    boolean verified = verificationPassed(type, value, previous, after);
                    if (!verified) {
                        listener.status("AGENT • VERIFY • UNCERTAIN • " + type);
                        SystemClock.sleep(RETRY_DELAY_MS);
                        String retry = screen();
                        verified = verificationPassed(type, value, previous, retry);
                        if (!verified) {
                            failures.add(type + "_verification");
                            listener.status("AGENT • RECOVERY • STEP NOT CONFIRMED");
                            break;
                        }
                        after = retry;
                    }
                    previous = after.isEmpty() ? previous : after;
                    listener.status("AGENT • VERIFY • PASS • " + type);
                }

                // UI mutations deliberately end the current reasoning turn. NovaBrain will
                // take a fresh accessibility snapshot and ask the model for the next action.
                listener.status("AGENT • STATE CHANGED • NEXT REASONING TURN");
                return result(true, false, 0, "", screen(), "", outputs.toString());
            }

            String finalScreen = screen();
            if (!failures.isEmpty()) listener.status("AGENT • RECOVERY REQUIRED • " + failures.get(0));
            else listener.status("AGENT • TASK VERIFIED");
            if (failures.isEmpty() && outputs.length() == 0 && !say.isEmpty()) listener.reply(say);
            return result(true, failures.isEmpty(), failures.size(), failures.isEmpty() ? "" : failures.get(0), finalScreen, say, outputs.toString());
        } catch (Exception e) {
            Log.e(TAG, "PLAN EXECUTION ERROR", e);
            listener.status("AGENT • SAFE STOP");
            return result(false, false, 1, "planner_exception", "", "", "");
        }
    }

    private ExecutionResult result(boolean valid, boolean complete, int failed, String action, String screen, String say, String tools) {
        return new ExecutionResult(valid, complete, failed, action, screen, say, tools);
    }

    private String executeOne(String type, String value) {
        try {
            if (isInformational(type) || "read_screen".equals(type)) return executor.executeTool(type, value);
            if ("parallel".equals(type)) return executor.executeParallel(value);
            if ("click_text".equals(type)) return boolResult(executor.clickText(value));
            if ("click_index".equals(type)) {
                try {
                    return boolResult(executor.clickVisibleIndex(Integer.parseInt(value)));
                } catch (NumberFormatException e) {
                    return "{\"ok\":false,\"error\":\"invalid_index\"}";
                }
            }
            if ("wait".equals(type)) {
                long ms;
                try { ms = Long.parseLong(value); } catch (NumberFormatException e) { ms = 500L; }
                ms = Math.max(100L, Math.min(ms, 2500L));
                SystemClock.sleep(ms);
                return "{\"ok\":true,\"waitMs\":" + ms + "}";
            }
            return boolResult(executor.execute(type, value));
        } catch (Exception e) {
            Log.e(TAG, "ACTION ERROR: " + type, e);
            return "{\"ok\":false,\"error\":\"tool_exception\"}";
        }
    }

    private boolean isInformational(String type) {
        return type.equals("web_search") || type.equals("web_fetch") || type.equals("web_research")
                || type.equals("screen_observe") || type.equals("memory_search") || type.equals("remember");
    }

    private boolean outputOk(String output) {
        if (output == null || output.trim().isEmpty()) return false;
        try { return new JSONObject(output).optBoolean("ok", true); }
        catch (Exception e) { return true; }
    }

    private String boolResult(boolean ok) {
        return "{\"ok\":" + ok + "}";
    }

    private void addResult(JSONArray array, int index, String type, String value, String output) throws Exception {
        array.put(new JSONObject()
                .put("step", index + 1)
                .put("tool", type)
                .put("value", value)
                .put("result", NovaAgentPolicy.bounded(output == null ? "" : output, NovaAgentPolicy.MAX_TOOL_RESULT_CHARS)));
    }

    private boolean shouldRetry(String type) {
        return NovaAgentPolicy.MAX_RETRIES > 0 && (type.equals("click_text") || type.equals("click_index")
                || type.equals("open_app") || type.equals("open_package") || type.equals("open_url")
                || type.equals("search") || type.equals("type_text") || type.equals("press_enter")
                || type.equals("web_search") || type.equals("web_fetch"));
    }

    private boolean needsVerification(String type) {
        return type.equals("open_app") || type.equals("open_package") || type.equals("open_url")
                || type.equals("click_text") || type.equals("click_index") || type.equals("search")
                || type.equals("type_text") || type.equals("press_enter")
                || type.startsWith("scroll_") || type.startsWith("swipe_");
    }

    private boolean verificationPassed(String type, String value, String before, String after) {
        if (after == null || after.isEmpty()) return false;
        if ("type_text".equals(type)) {
            // ACTION_SET_TEXT returning true is already direct platform evidence; a UI snapshot
            // may omit editable values on some apps, so do not reject successful input solely
            // because the serialized accessibility tree is unchanged.
            return true;
        }
        if (before.isEmpty()) return true;
        if (!after.equals(before)) return true;
        return "click_text".equals(type) && !value.isEmpty() && !after.toLowerCase().contains(value.toLowerCase());
    }

    private String screen() {
        String value = executor.readScreen();
        return value == null ? "" : NovaAgentPolicy.bounded(value.replaceAll("\\s+", " ").trim(), MAX_SCREEN_CHARS);
    }

    private JSONObject parseObject(String raw) throws Exception {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return new JSONObject(text.substring(start, end + 1));
    }
}
