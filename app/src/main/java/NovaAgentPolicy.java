package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** Central safety/resource policy for every agent action. */
public final class NovaAgentPolicy {
    public static final int MAX_STEPS = 8;
    public static final int MAX_RETRIES = 2;
    public static final long MAX_TASK_MILLIS = 60_000L;
    public static final int MAX_TOOL_RESULT_CHARS = 16_384;
    public static final int MAX_CONTEXT_ITEMS = 24;

    public enum Decision { ALLOW, REQUIRE_CONFIRMATION, BLOCK }

    private NovaAgentPolicy() { }

    public static boolean taskExpired(long startedAt) {
        return startedAt <= 0 || System.currentTimeMillis() - startedAt > MAX_TASK_MILLIS;
    }

    public static String bounded(String value, int max) {
        if (value == null) return "";
        if (max <= 0 || value.length() <= max) return value;
        return value.substring(0, max);
    }

    /** Central gate: unknown, malformed, conflicting, and sensitive actions fail closed. */
    public static Decision evaluateAction(String type, String value) {
        String action = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        String v = value == null ? "" : value.trim();
        if (action.isEmpty() || !NovaActionSchema.isKnown(action)) return Decision.BLOCK;
        if (v.length() > 4096) return Decision.BLOCK;
        if ("open_url".equals(action) && !isWebUrl(v)) return Decision.BLOCK;
        if ("parallel".equals(action) && !parallelIsInformational(v)) return Decision.BLOCK;
        if ("type_text".equals(action) && looksCredentialLike(v)) return Decision.REQUIRE_CONFIRMATION;
        return Decision.ALLOW;
    }

    /** Parallel execution is reserved for independent read-only/informational tools. */
    private static boolean parallelIsInformational(String value) {
        try {
            JSONArray steps = new JSONArray(value);
            if (steps.length() == 0 || steps.length() > MAX_STEPS) return false;
            for (int i = 0; i < steps.length(); i++) {
                JSONObject step = steps.optJSONObject(i);
                if (step == null) return false;
                String type = NovaActionSchema.normalizeType(step.optString("type", ""));
                if (!NovaActionSchema.isKnown(type) || !NovaActionSchema.canRunInParallel(type)) return false;
                if (!NovaActionSchema.validate(step).isEmpty()) return false;
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static boolean requiresConfirmation(String type, String value) {
        return evaluateAction(type, value) == Decision.REQUIRE_CONFIRMATION;
    }

    public static boolean isWebUrl(String value) {
        if (value == null || value.trim().isEmpty()) return false;
        String v = value.trim().toLowerCase(Locale.ROOT);
        return v.startsWith("http://") || v.startsWith("https://");
    }

    public static boolean looksCredentialLike(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.matches("(?is).*\\b(bearer\\s+[A-Za-z0-9._~+/=-]{8,}|api[_ -]?key\\s*[:=]\\s*\\S+|password\\s*[:=]\\s*\\S+|passwd\\s*[:=]\\s*\\S+|authorization\\s*[:=]\\s*\\S+|private[_ -]?key\\s*[:=]\\s*\\S+).*")
                || v.matches("(?is).*\\bsk-[A-Za-z0-9_-]{16,}.*")
                || v.matches("(?is).*\\bAIza[0-9A-Za-z_-]{20,}.*");
    }
}
