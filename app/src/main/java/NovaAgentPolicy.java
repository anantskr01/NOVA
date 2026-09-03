package com.aircontrol;

/** Central resource limits shared by NOVA's autonomous execution layers. */
public final class NovaAgentPolicy {
    public static final int MAX_STEPS = 8;
    public static final int MAX_RETRIES = 2;
    public static final long MAX_TASK_MILLIS = 60_000L;
    public static final int MAX_TOOL_RESULT_CHARS = 16_384;
    public static final int MAX_CONTEXT_ITEMS = 24;

    private NovaAgentPolicy() { }

    public static boolean taskExpired(long startedAt) {
        return startedAt <= 0 || System.currentTimeMillis() - startedAt > MAX_TASK_MILLIS;
    }

    public static String bounded(String value, int max) {
        if (value == null) return "";
        if (max <= 0 || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
