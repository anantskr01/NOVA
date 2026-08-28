package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Lightweight persistent state for NOVA's current and most recent task. */
public final class NovaTaskStore {
    private static final String PREFS = "nova_task_state";
    private static final String GOAL = "goal";
    private static final String ITERATION = "iteration";
    private static final String LAST_RESULT = "last_result";
    private static final String HISTORY = "history";
    private static final String RUNNING = "running";
    private static final String COMPLETED = "completed";
    private static final String STARTED_AT = "started_at";
    private static final String FINISHED_AT = "finished_at";
    private static final int MAX_HISTORY_CHARS = 12000;

    private final SharedPreferences prefs;

    public NovaTaskStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void begin(String goal) {
        long now = System.currentTimeMillis();
        prefs.edit()
                .putString(GOAL, goal == null ? "" : goal.trim())
                .putInt(ITERATION, 0)
                .putString(LAST_RESULT, "")
                .putString(HISTORY, "")
                .putBoolean(RUNNING, true)
                .putBoolean(COMPLETED, false)
                .putLong(STARTED_AT, now)
                .remove(FINISHED_AT)
                .apply();
    }

    public synchronized void setIteration(int iteration) {
        prefs.edit().putInt(ITERATION, Math.max(0, iteration)).apply();
    }

    public synchronized int getIteration() {
        return prefs.getInt(ITERATION, 0);
    }

    public synchronized String getGoal() {
        return prefs.getString(GOAL, "");
    }

    public synchronized void setLastResult(String result) {
        prefs.edit().putString(LAST_RESULT, result == null ? "" : result).apply();
    }

    public synchronized String lastResult() {
        return prefs.getString(LAST_RESULT, "");
    }

    public synchronized void appendHistory(String entry) {
        if (entry == null || entry.trim().isEmpty()) return;
        String current = prefs.getString(HISTORY, "");
        String next = (current == null || current.isEmpty())
                ? entry.trim() : current + "\n" + entry.trim();
        if (next.length() > MAX_HISTORY_CHARS) {
            next = next.substring(next.length() - MAX_HISTORY_CHARS);
        }
        prefs.edit().putString(HISTORY, next).apply();
    }

    public synchronized String history() {
        return prefs.getString(HISTORY, "");
    }

    public synchronized boolean isRunning() {
        return prefs.getBoolean(RUNNING, false);
    }

    public synchronized boolean isCompleted() {
        return prefs.getBoolean(COMPLETED, false);
    }

    public synchronized long startedAt() {
        return prefs.getLong(STARTED_AT, 0L);
    }

    public synchronized long finishedAt() {
        return prefs.getLong(FINISHED_AT, 0L);
    }

    public synchronized void finish() {
        finish(false);
    }

    public synchronized void finish(boolean completed) {
        prefs.edit()
                .putBoolean(RUNNING, false)
                .putBoolean(COMPLETED, completed)
                .putLong(FINISHED_AT, System.currentTimeMillis())
                .apply();
    }

    public synchronized void clear() {
        prefs.edit().clear().apply();
    }
}
