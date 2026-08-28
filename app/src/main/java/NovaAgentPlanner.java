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

    public interface ActionExecutor {
        boolean execute(String type, String value);
        String readScreen();
        boolean clickText(String text);
        boolean clickVisibleIndex(int index);

        /** Optional non-UI tool hook. */
        default String executeTool(String type, String value) { return null; }
    }

    public interface Listener {
        void status(String text);
        void reply(String text);
    }

    private final ActionExecutor executor;
    private final Listener listener;
    private final NovaToolRegistry registry;

    public NovaAgentPlanner(ActionExecutor executor, Listener listener) {
        this.executor = executor;
        this.listener = listener;
        this.registry = new NovaToolRegistry();
    }

    public String toolSummary() { return registry.promptSummary(); }
    public boolean isKnownTool(String type) { return registry.contains(type); }

    public boolean execute(String rawPlan) {
        try {
            JSONObject plan = parseObject(rawPlan);
            if (plan == null) return false;

            String say = plan.optString("say", "").trim();
            JSONArray actions = plan.optJSONArray("actions");
            if (actions == null) {
                if (!say.isEmpty()) listener.reply(say);
                return true;
            }

            int count = Math.min(actions.length(), MAX_STEPS);
            listener.status("AGENT • EXECUTING " + count + " STEPS");
            List<String> failures = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                JSONObject action = actions.optJSONObject(i);
                if (action == null) continue;

                String type = action.optString("type", "none").trim().toLowerCase(Locale.ROOT);
                String value = action.optString("value", "").trim();

                if (!registry.validate(type, value)) {
                    failures.add(type.isEmpty() ? "unknown" : type);
                    listener.status("AGENT • BLOCKED INVALID TOOL");
                    continue;
                }

                if (registry.requiresConfirmation(type)) {
                    failures.add(type);
                    listener.status("AGENT • CONFIRMATION REQUIRED • " + type);
                    continue;
                }

                if ("none".equals(type)) continue;
                listener.status("AGENT • STEP " + (i + 1) + "/" + count + " • " + type);

                boolean ok;
                if (type.contains(".")) {
                    ok = executeExternalTool(type, value);
                } else {
                    ok = executeOne(type, value);
                    if (!ok && MAX_RETRIES > 0) ok = executeOne(type, value);
                }
                if (!ok) failures.add(type);
            }

            String screen = executor.readScreen();
            if (screen != null && !screen.trim().isEmpty()) listener.status("AGENT • VERIFIED CURRENT UI");
            if (!failures.isEmpty()) listener.status("AGENT • " + failures.size() + " ACTION(S) NOT COMPLETED");
            if (!say.isEmpty()) listener.reply(say);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PLAN EXECUTION ERROR", e);
            return false;
        }
    }

    private boolean executeExternalTool(String type, String value) {
        try {
            String result = executor.executeTool(type, value);
            if (result == null || result.trim().isEmpty()) return false;
            listener.status("TOOL • RESULT RECEIVED");
            listener.reply(result);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "TOOL ERROR: " + type, e);
            return false;
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
                if (value.isEmpty()) return false;
                String lower = value.toLowerCase(Locale.ROOT);
                if (!(lower.startsWith("https://") || lower.startsWith("http://"))) return false;
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
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        return new JSONObject(text.substring(start, end + 1));
    }
}
