package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;

/** Lightweight persistent state for NOVA's current and most recent task. */
public final class NovaTaskStore {
    private static final String PREFS = "nova_task_state";
    private static final String GOAL = "goal";
    private static final String ITERATION = "iteration";
    private static final String LAST_RESULT = "last_result";
    private static final String RUNNING = "running";

    private final SharedPreferences prefs;

    public NovaTaskStore(Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void begin(String goal) {
        prefs.edit()
                .putString(GOAL, goal == null ? "" : goal.trim())
                .putInt(ITERATION, 0)
                .putString(LAST_RESULT, "")
                .putBoolean(RUNNING, true)
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

    public synchronized boolean isRunning() {
        return prefs.getBoolean(RUNNING, false);
    }

    public synchronized void finish() {
        prefs.edit().putBoolean(RUNNING, false).apply();
    }

    public synchronized void clear() {
        prefs.edit().clear().apply();
    }
}
