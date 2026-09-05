package com.aircontrol;

import android.os.SystemClock;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

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

    public interface ActionExecutor {
        boolean execute(String type, String value);
        String readScreen();
        boolean clickText(String text);
        boolean clickVisibleIndex(int index);
        default String readUiState() { return readScreen(); }
        default String activePackageName() { return ""; }
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

    public NovaAgentPlanner(ActionExecutor e, Listener l) { this(e, l, new NovaToolRegistry()); }

    public NovaAgentPlanner(ActionExecutor e, Listener l, NovaToolRegistry t) {
        executor = e;
        listener = l;
        tools = t == null ? new NovaToolRegistry() : t;
    }

    public boolean execute(String rawPlan) { return executeDetailed(rawPlan).completed; }

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

            listener.status("AGENT • OBSERVE → UNDERSTAND → ACT → VERIFY");
            List<String> failures = new ArrayList<>();
            JSONArray outputs = new JSONArray();
            String previous = screen();
            String previousPackage = safePackage();

            for (int i = 0; i < actions.length(); i++) {
                if (NovaAgentPolicy.taskExpired(started)) {
                    failures.add("task_timeout");
                    listener.status("AGENT • SAFE STOP • TASK TIMEOUT");
                    break;
                }

                JSONObject action = actions.optJSONObject(i);
                if (action == null) {
                    failures.add("malformed_step_" + (i + 1));
                    listener.status("AGENT • BLOCKED INVALID ACTION • malformed_step_" + (i + 1));
                    break;
                }

                String type = action.optString("type", "").trim().toLowerCase();
                String validation = NovaActionSchema.validate(action);
                if (!tools.contains(type) || !NovaActionSchema.isKnown(type) || !validation.isEmpty()) {
                    String reason = validation.isEmpty() ? "unknown_action:" + type : validation;
                    listener.status("AGENT • BLOCKED INVALID ACTION • " + reason);
                    failures.add(reason);
                    break;
                }

                String value = NovaAgentPolicy.bounded(action.optString("value", "").trim(), 2048);
                if ("none".equals(type)) continue;

                String before = screen();
                String beforePackage = safePackage();
                listener.status("AGENT • STEP " + (i + 1) + "/" + actions.length() + " • OBSERVE");
                listener.status("AGENT • STEP " + (i + 1) + "/" + actions.length() + " • ACT • " + type);
                NovaDiagnostics.event("action_validated", type);

                String output = executeOne(type, value);
                boolean ok = outputOk(output);
                if (!ok && shouldRetry(type)) {
                    SystemClock.sleep(RETRY_DELAY_MS);
                    listener.status("AGENT • RETRY • " + type);
                    NovaDiagnostics.event("action_retry", type);
                    output = executeOne(type, value);
                    ok = outputOk(output);
                }

                if (!ok) {
                    addResult(outputs, i, type, value, output, before, "", false, beforePackage, safePackage());
                    failures.add(type);
                    listener.status("AGENT • RECOVERY NEEDED • " + type);
                    NovaDiagnostics.event("action_failed", type);
                    break;
                }

                if (isInformational(type) || "parallel".equals(type)) {
                    addResult(outputs, i, type, value, output, before, before, true, beforePackage, beforePackage);
                    NovaDiagnostics.event("tool_executed", type);
                    continue;
                }

                if ("wait".equals(type)) {
                    String waited = screen();
                    addResult(outputs, i, type, value, output, before, waited, true, beforePackage, safePackage());
                    previous = waited;
                    previousPackage = safePackage();
                    continue;
                }

                String after = screen();
                String afterPackage = safePackage();
                boolean verified = !needsVerification(type)
                        || verificationPassed(type, value, before, after, beforePackage, afterPackage);

                if (!verified) {
                    listener.status("AGENT • VERIFY • UNCERTAIN • " + type);
                    SystemClock.sleep(VERIFY_DELAY_MS);
                    String retry = screen();
                    String retryPackage = safePackage();
                    verified = verificationPassed(type, value, before, retry, beforePackage, retryPackage);
                    if (verified) {
                        after = retry;
                        afterPackage = retryPackage;
                    }
                }

                addResult(outputs, i, type, value, output, before, after, verified, beforePackage, afterPackage);

                if (!verified) {
                    failures.add(type + "_verification");
                    listener.status("AGENT • RECOVERY • STEP NOT CONFIRMED");
                    NovaDiagnostics.event("verification_failed", type);
                    break;
                }

                previous = after.isEmpty() ? previous : after;
                previousPackage = afterPackage.isEmpty() ? previousPackage : afterPackage;
                listener.status("AGENT • VERIFY • PASS • " + type);
                NovaDiagnostics.event("verification_passed", type);

                // Exactly one Android UI mutation per reasoning turn. NovaBrain receives the
                // fresh observation and asks the model what should happen next.
                listener.status("AGENT • STATE OBSERVED • NEXT REASONING TURN");
                return result(true, false, 0, "", screen(), "", outputs.toString());
            }

            String finalScreen = screen();
            if (!failures.isEmpty()) {
                listener.status("AGENT • RECOVERY REQUIRED • " + failures.get(0));
            } else {
                listener.status("AGENT • TASK VERIFIED");
            }
            if (failures.isEmpty() && outputs.length() == 0 && !say.isEmpty()) listener.reply(say);
            return result(true, failures.isEmpty(), failures.size(), failures.isEmpty() ? "" : failures.get(0), finalScreen, say, outputs.toString());
        } catch (Exception e) {
            Log.e(TAG, "PLAN EXECUTION ERROR", e);
            NovaDiagnostics.event("planner_exception", e.getClass().getSimpleName());
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
                try { return boolResult(executor.clickVisibleIndex(Integer.parseInt(value))); }
                catch (NumberFormatException e) { return "{\"ok\":false,\"error\":\"invalid_index\"}"; }
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
            NovaDiagnostics.event("tool_exception", type);
            return "{\"ok\":false,\"error\":\"tool_exception\"}";
        }
    }

    private boolean isInformational(String type) { return NovaActionSchema.isInformational(type); }

    private boolean outputOk(String output) {
        if (output == null || output.trim().isEmpty()) return false;
        try { return new JSONObject(output).optBoolean("ok", true); }
        catch (Exception e) { return true; }
    }

    private String boolResult(boolean ok) { return "{\"ok\":" + ok + "}"; }

    private void addResult(JSONArray array, int index, String type, String value, String output,
                           String before, String after, boolean verified,
                           String beforePackage, String afterPackage) throws Exception {
        array.put(new JSONObject()
                .put("step", index + 1)
                .put("tool", type)
                .put("value", value)
                .put("ok", outputOk(output))
                .put("verified", verified)
                .put("beforePackage", beforePackage == null ? "" : beforePackage)
                .put("afterPackage", afterPackage == null ? "" : afterPackage)
                .put("before", NovaAgentPolicy.bounded(before == null ? "" : before, NovaAgentPolicy.MAX_TOOL_RESULT_CHARS))
                .put("after", NovaAgentPolicy.bounded(after == null ? "" : after, NovaAgentPolicy.MAX_TOOL_RESULT_CHARS))
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

    private boolean verificationPassed(String type, String value, String before, String after,
                                       String beforePackage, String afterPackage) {
        if (after == null || after.isEmpty()) return false;

        if ("type_text".equals(type)) {
            // Never treat a successful API call as proof. Prefer visible evidence of the entered
            // value; for masked/password fields, a changed accessibility snapshot is the strongest
            // evidence available without reading or exposing the secret itself.
            if (!value.isEmpty() && after.contains(value)) return true;
            return !after.equals(before);
        }

        if (("open_app".equals(type) || "open_package".equals(type) || "open_url".equals(type))
                && !afterPackage.isEmpty()) {
            return !afterPackage.equals(beforePackage) || !after.equals(before);
        }

        if ("click_text".equals(type)) {
            if (value.isEmpty() || after.equals(before)) return false;
            String target = value.toLowerCase();
            String b = before.toLowerCase();
            String a = after.toLowerCase();
            // A changed screen is not sufficient by itself when the intended target remains
            // completely untouched. Accept a target disappearance/change, focus/state evidence,
            // or a package transition as concrete UI evidence.
            boolean targetChanged = b.contains(target) && !a.contains(target);
            boolean stateEvidence = a.contains("focused=true") || a.contains("selected=true")
                    || a.contains("checked=true") || a.contains("enabled=false");
            return targetChanged || stateEvidence || !afterPackage.equals(beforePackage);
        }

        // For scrolling/swiping and other UI mutations, require an observable accessibility or
        // package-state change. At an unchanged edge, verification fails and Brain gets a chance
        // to recover instead of falsely claiming the gesture worked.
        return !after.equals(before) || !afterPackage.equals(beforePackage);
    }

    private String screen() {
        String value = executor.readUiState();
        return value == null ? "" : NovaAgentPolicy.bounded(value.replaceAll("\\s+", " ").trim(), MAX_SCREEN_CHARS);
    }

    private String safePackage() {
        try {
            String value = executor.activePackageName();
            return value == null ? "" : value.trim();
        } catch (Exception e) {
            return "";
        }
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
