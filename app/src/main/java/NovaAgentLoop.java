package com.aircontrol;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded observe → plan → act → verify → re-plan loop for NOVA. */
public final class NovaAgentLoop {
    private static final String TAG = "NovaAgentLoop";
    private static final int MAX_ITERATIONS = 4;
    public interface Callback { void status(String text); void reply(String text); }
    private final NovaAiClient ai;
    private final NovaAgentPlanner planner;
    private final NovaMemory memory;
    private final NovaTaskStore taskStore;
    private final Callback callback;
    private boolean running;

    public NovaAgentLoop(NovaAiClient ai, NovaAgentPlanner planner, NovaMemory memory,
                         NovaTaskStore taskStore, Callback callback) {
        this.ai = ai; this.planner = planner; this.memory = memory;
        this.taskStore = taskStore; this.callback = callback;
    }

    public synchronized void start(String goal, String endpoint, String apiKey, String model) {
        if (running) { callback.reply("I'm still working on the previous task."); return; }
        running = true; taskStore.begin(goal); iterate(goal, endpoint, apiKey, model, 0);
    }

    private void iterate(String goal, String endpoint, String apiKey, String model, int iteration) {
        taskStore.setIteration(iteration);
        if (iteration >= MAX_ITERATIONS) {
            finish(false, "I stopped after the safe task limit. The task may need another instruction.");
            return;
        }
        callback.status("AGENT • OBSERVE → PLAN • " + (iteration + 1) + "/" + MAX_ITERATIONS);
        String screen = planner.currentScreen();
        String failures = planner.lastFailuresSummary();
        try {
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "You are NOVA's bounded autonomous agent. Produce only the next useful plan. " +
                    "Return JSON only: {\"say\":\"short response\",\"actions\":[{\"type\":\"ACTION\",\"value\":\"VALUE\"}]}. " +
                    "Maximum 8 actions. Registered tools:\n" + planner.toolSummary() + "\n" +
                    "Observe before acting when needed. Never claim success without execution evidence. " +
                    "If the goal is complete, return an empty actions array. Previous failures: " +
                    (failures.isEmpty() ? "none" : failures));
            messages.put(system);
            JSONObject context = new JSONObject();
            context.put("role", "system");
            context.put("content", "CURRENT SCREEN:\n" + safe(screen) + "\n\nMEMORY:\n" + safe(memory.factsSummary()) +
                    "\n\nLAST TASK RESULT:\n" + safe(taskStore.lastResult()) + "\n\nTASK:\n" + goal);
            messages.put(context);
            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", iteration == 0 ? goal : "Continue from the current observed state. Re-plan only what remains and do not repeat confirmed successful actions.");
            messages.put(user);
            ai.chat(endpoint, apiKey, model, messages, new NovaAiClient.Callback() {
                @Override public void onResult(String response) {
                    try {
                        callback.status("AGENT • PLAN RECEIVED");
                        boolean parsed = planner.execute(response);
                        taskStore.setLastResult(planner.lastFailuresSummary());
                        if (!parsed) { finish(false, "I couldn't understand the plan returned by my AI core."); return; }
                        if (planner.lastExecutionSuccessful() && planner.lastFailuresSummary().isEmpty()) { finish(true, null); return; }
                        iterate(goal, endpoint, apiKey, model, iteration + 1);
                    } catch (Exception e) { Log.e(TAG, "LOOP RESULT ERROR", e); finish(false, "I couldn't complete the task safely."); }
                }
                @Override public void onError(String message) {
                    Log.e(TAG, "LOOP AI ERROR: " + message); finish(false, "My AI core is unavailable while working on that task.");
                }
            });
        } catch (Exception e) { Log.e(TAG, "LOOP ERROR", e); finish(false, "I couldn't start the agent loop."); }
    }

    private synchronized void finish(boolean success, String message) {
        running = false; taskStore.finish();
        callback.status(success ? "AGENT • TASK COMPLETE" : "AGENT • TASK STOPPED");
        if (message != null && !message.trim().isEmpty()) callback.reply(message);
    }
    private String safe(String value) { return value == null || value.trim().isEmpty() ? "None available." : value; }
    public synchronized void stop() { running = false; taskStore.finish(); callback.status("AGENT • STOPPED"); }
    public synchronized boolean isRunning() { return running; }
}
