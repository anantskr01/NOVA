package com.aircontrol;

import android.os.SystemClock;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Bounded autonomous planner for NOVA: observe -> act -> verify -> recover. */
public final class NovaAgentPlanner {
    private static final String TAG = "NovaAgentPlanner";
    private static final long VERIFY_DELAY_MS = 450L;
    private static final long RETRY_DELAY_MS = 350L;
    private static final int MAX_SCREEN_CHARS = 5000;

    public static final class ExecutionResult {
        public final boolean planValid, completed;
        public final int failedSteps;
        public final String failedAction, finalScreen, say;
        ExecutionResult(boolean planValid, boolean completed, int failedSteps, String failedAction, String finalScreen, String say) {
            this.planValid = planValid; this.completed = completed; this.failedSteps = failedSteps;
            this.failedAction = failedAction == null ? "" : failedAction;
            this.finalScreen = finalScreen == null ? "" : finalScreen;
            this.say = say == null ? "" : say;
        }
    }

    private static final Set<String> ALLOWED = new HashSet<>();
    static {
        String[] values = {"home","back","recents","notifications","quick_settings","scroll_up","scroll_down",
                "swipe_left","swipe_right","open_url","open_package","open_app","click_text","click_index",
                "search","read_screen","settings","wait","none"};
        for (String value : values) ALLOWED.add(value);
    }

    public interface ActionExecutor {
        boolean execute(String type, String value);
        String readScreen();
        boolean clickText(String text);
        boolean clickVisibleIndex(int index);
    }
    public interface Listener { void status(String text); void reply(String text); }

    private final ActionExecutor executor;
    private final Listener listener;
    private final NovaToolRegistry tools;

    public NovaAgentPlanner(ActionExecutor executor, Listener listener) { this(executor, listener, new NovaToolRegistry()); }
    public NovaAgentPlanner(ActionExecutor executor, Listener listener, NovaToolRegistry tools) {
        this.executor = executor; this.listener = listener; this.tools = tools == null ? new NovaToolRegistry() : tools;
    }
    public boolean execute(String rawPlan) { return executeDetailed(rawPlan).completed; }

    public ExecutionResult executeDetailed(String rawPlan) {
        final long started = System.currentTimeMillis();
        try {
            JSONObject plan = parseObject(rawPlan);
            if (plan == null) {
                listener.status("AGENT • INVALID PLAN");
                return new ExecutionResult(false, false, 1, "invalid_plan", "", "");
            }
            String say = plan.optString("say", "").trim();
            JSONArray actions = plan.optJSONArray("actions");
            if (actions == null) {
                if (!say.isEmpty()) listener.reply(say);
                return new ExecutionResult(true, true, 0, "", normalizeScreen(executor.readScreen()), say);
            }

            // Never silently execute only the first N steps of an over-limit plan.
            // The model must receive a bounded plan; silently truncating it can make
            // NOVA report success while the user's full goal was not attempted.
            if (actions.length() > NovaAgentPolicy.MAX_STEPS) {
                listener.status("AGENT • PLAN REJECTED • STEP LIMIT");
                return new ExecutionResult(false, false, 1, "step_limit_exceeded", normalizeScreen(executor.readScreen()), "");
            }

            int count = actions.length();
            listener.status("AGENT • OBSERVE → PLAN → ACT → VERIFY");
            List<String> failures = new ArrayList<>();
            String previousScreen = normalizeScreen(executor.readScreen());

            for (int i = 0; i < count; i++) {
                if (NovaAgentPolicy.taskExpired(started)) {
                    failures.add("task_timeout");
                    listener.status("AGENT • SAFE STOP • TASK TIMEOUT");
                    break;
                }
                JSONObject action = actions.optJSONObject(i);
                if (action == null) { failures.add("malformed_step_" + (i + 1)); continue; }
                String type = action.optString("type", "none").trim().toLowerCase();
                String value = NovaAgentPolicy.bounded(action.optString("value", "").trim(), 2048);
                if (!tools.contains(type) || !ALLOWED.contains(type)) {
                    listener.status("AGENT • BLOCKED UNKNOWN ACTION • " + type); failures.add(type); break;
                }
                if ("none".equals(type)) continue;

                listener.status("AGENT • STEP " + (i + 1) + "/" + count + " • OBSERVE");
                String before = normalizeScreen(executor.readScreen());
                if (!before.isEmpty()) previousScreen = before;
                listener.status("AGENT • STEP " + (i + 1) + "/" + count + " • ACT • " + type);

                boolean ok = executeOne(type, value);
                if (!ok && shouldRetry(type)) {
                    SystemClock.sleep(RETRY_DELAY_MS);
                    listener.status("AGENT • RETRY • " + type);
                    ok = executeOne(type, value);
                }
                if (!ok) { failures.add(type); listener.status("AGENT • RECOVERY NEEDED • " + type); break; }

                if (needsVerification(type)) {
                    SystemClock.sleep(VERIFY_DELAY_MS);
                    String after = normalizeScreen(executor.readScreen());
                    if (isMeaningfulChange(type, value, previousScreen, after)) {
                        listener.status("AGENT • VERIFY • PASS");
                        previousScreen = after.isEmpty() ? previousScreen : after;
                    } else {
                        listener.status("AGENT • VERIFY • UNCERTAIN");
                        SystemClock.sleep(RETRY_DELAY_MS);
                        String retryScreen = normalizeScreen(executor.readScreen());
                        if (!isMeaningfulChange(type, value, previousScreen, retryScreen)) {
                            failures.add(type + "_verification");
                            listener.status("AGENT • RECOVERY • STEP NOT CONFIRMED");
                            break;
                        }
                        previousScreen = retryScreen;
                        listener.status("AGENT • VERIFY • PASS AFTER WAIT");
                    }
                }
            }

            String finalScreen = normalizeScreen(executor.readScreen());
            if (!failures.isEmpty()) listener.status("AGENT • RECOVERY REQUIRED • " + failures.get(0));
            else listener.status("AGENT • TASK VERIFIED");
            if (failures.isEmpty() && !say.isEmpty()) listener.reply(say);
            return new ExecutionResult(true, failures.isEmpty(), failures.size(), failures.isEmpty() ? "" : failures.get(0), finalScreen, say);
        } catch (Exception e) {
            Log.e(TAG, "PLAN EXECUTION ERROR", e);
            listener.status("AGENT • SAFE STOP");
            return new ExecutionResult(false, false, 1, "planner_exception", "", "");
        }
    }

    private boolean executeOne(String type, String value) {
        try {
            if ("read_screen".equals(type)) { String text = executor.readScreen(); listener.status(text == null ? "SCREEN • UNAVAILABLE" : text); return text != null; }
            if ("click_text".equals(type)) return executor.clickText(value);
            if ("click_index".equals(type)) { try { return executor.clickVisibleIndex(Integer.parseInt(value)); } catch (NumberFormatException ignored) { return false; } }
            if ("wait".equals(type)) { long ms; try { ms = Long.parseLong(value); } catch (NumberFormatException ignored) { ms = 500L; } SystemClock.sleep(Math.max(100L, Math.min(ms, 2500L))); return true; }
            return executor.execute(type, value);
        } catch (Exception e) { Log.e(TAG, "ACTION ERROR: " + type, e); return false; }
    }

    private boolean shouldRetry(String type) {
        return NovaAgentPolicy.MAX_RETRIES > 0 && ("click_text".equals(type) || "click_index".equals(type)
                || "open_app".equals(type) || "open_package".equals(type) || "open_url".equals(type) || "search".equals(type));
    }
    private boolean needsVerification(String type) {
        return "open_app".equals(type) || "open_package".equals(type) || "open_url".equals(type) || "click_text".equals(type)
                || "click_index".equals(type) || "search".equals(type) || "scroll_up".equals(type) || "scroll_down".equals(type)
                || "swipe_left".equals(type) || "swipe_right".equals(type);
    }
    private boolean isMeaningfulChange(String type, String value, String before, String after) {
        if (after == null || after.isEmpty()) return false;
        if (before.isEmpty()) return true;
        if (!after.equals(before)) return true;
        return "click_text".equals(type) && !value.isEmpty() && !after.toLowerCase().contains(value.toLowerCase());
    }
    private String normalizeScreen(String screen) {
        if (screen == null) return "";
        String value = screen.replaceAll("\\s+", " ").trim();
        return NovaAgentPolicy.bounded(value, MAX_SCREEN_CHARS);
    }
    private JSONObject parseObject(String raw) throws Exception {
        if (raw == null) return null;
        String text = raw.trim();
        if (text.startsWith("```")) text = text.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        int start = text.indexOf('{'), end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return new JSONObject(text.substring(start, end + 1));
    }
}
