package com.aircontrol;

/** Central resource limits shared by NOVA's autonomous execution layers. */
public final class NovaAgentPolicy {
    public static final int MAX_STEPS = 8;
    public static final int MAX_RETRIES = 2;
    public static final long MAX_TASK_MILLIS = 60_000L;
    public static final long MAX_CODING_TASK_MILLIS = 300_000L;
    public static final int MAX_CONTEXT_ITEMS = 24;
    public static final int MAX_GENERAL_AGENT_TURNS = 8;
    public static final int MAX_CODING_AGENT_TURNS = 32;
    public static final int MAX_GENERAL_RECOVERY_ATTEMPTS = 1;
    public static final int MAX_CODING_RECOVERY_ATTEMPTS = 6;
    public static final int MAX_TOOL_RESULT_CHARS = 16_384;

    private NovaAgentPolicy() { }

    public static boolean taskExpired(long startedAt) {
        return taskExpired(startedAt, false);
    }

    public static boolean taskExpired(long startedAt, boolean codingTask) {
        return startedAt <= 0 || System.currentTimeMillis() - startedAt > (codingTask ? MAX_CODING_TASK_MILLIS : MAX_TASK_MILLIS);
    }

    public static int maxAgentTurns(boolean codingTask) {
        return codingTask ? MAX_CODING_AGENT_TURNS : MAX_GENERAL_AGENT_TURNS;
    }

    public static int maxRecoveryAttempts(boolean codingTask) {
        return codingTask ? MAX_CODING_RECOVERY_ATTEMPTS : MAX_GENERAL_RECOVERY_ATTEMPTS;
    }

    public static boolean isCodingGoal(String goal) {
        if (goal == null) return false;
        String g = goal.trim().toLowerCase();
        return g.matches(".*\\b(code|coding|program|programming|developer|develop|debug|debugging|fix|bug|compile|build|test|tests|repository|repo|github|git|java|kotlin|python|javascript|typescript|android app|project|implement|refactor|modify|edit|write code|create file|update file)\\b.*");
    }

    public static String bounded(String value, int max) {
        if (value == null) return "";
        if (max <= 0 || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
