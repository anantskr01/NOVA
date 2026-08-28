package com.aircontrol;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/** Bounded observe/act/re-plan loop for multi-step NOVA tasks. */
public final class NovaAgentLoop {
    private static final String TAG = "NovaAgentLoop";
    private static final int MAX_ITERATIONS = 4;

    public interface Callback {
        void status(String text);
        void reply(String text);
    }

    private final NovaAiClient ai;
    private final NovaAgentPlanner planner;
    private final NovaMemory memory;
    private final Callback callback;
    private boolean running;

    public NovaAgentLoop(NovaAiClient ai, NovaAgentPlanner planner, NovaMemory memory, Callback callback) {
        this.ai = ai;
        this.planner = planner;
        this.memory = memory;
        this.callback = callback;
    }

    public void start(String goal, String endpoint, String apiKey, String model) {
        if (running) {
            callback.reply("I'm still working on the previous task.");
            return;
        }
        running = true;
        iterate(goal, endpoint, apiKey, model, 0);
    }

    private void iterate(String goal, String endpoint, String apiKey, String model, int iteration) {
        if (iteration >= MAX_ITERATIONS) {
            running = false;
            callback.status("AGENT • ITERATION LIMIT REACHED");
            callback.reply("I stopped after the safe task limit. The task may need another instruction.");
            return;
        }

        callback.status("AGENT • OBSERVE → PLAN • " + (iteration + 1) + "/" + MAX_ITERATIONS);
        String screen = planner.currentScreen();
        String failures = planner.lastFailuresSummary();

        try {
            JSONArray messages = new JSONArray();
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", "You are NOVA's bounded agent loop. " +
                    "Create only the next useful action plan for the user's goal. " +
                    "Return JSON only: {\"say\":\"short response\",\"actions\":[{\"type\":\"ACTION\",\"value\":\"VALUE\"}]}. " +
                    "Maximum 8 actions in a plan. Use the registered tools from the existing NOVA tool catalog. " +
                    "Observe the current state before acting when needed. Never claim success without execution evidence. " +
                    "If the goal is already complete, return {\"say\":\"done\",\"actions\":[]}. " +
                    "Previous failed actions: " + (failures.isEmpty() ? "none" : failures));
            messages.put(system);

            JSONObject context = new JSONObject();
            context.put("role", "system");
            context.put("content", "CURRENT SCREEN:\n" + safe(screen) +
                    "\n\nMEMORY:\n" + safe(memory.factsSummary()) +
                    "\n\nTASK:\n" + goal);
            messages.put(context);

            JSONObject user = new JSONObject();
            user.put("role", "user");
            user.put("content", iteration == 0 ? goal : "Continue the task from the current observed state. Re-plan only what remains.");
            messages.put(user);

            ai.chat(endpoint, apiKey, model, messages, new NovaAiClient.Callback() {
                @Override public void onResult(String response) {
                    try {
                        callback.status("AGENT • PLAN RECEIVED");
                        boolean parsed = planner.execute(response);
                        if (!parsed) {
                            running = false;
                            callback.reply("I couldn't understand the plan returned by my AI core.");
                            return;
                        }

                        if (planner.lastExecutionSuccessful() && planner.lastFailuresSummary().isEmpty()) {
                            running = false;
                            callback.status("AGENT • TASK COMPLETE");
                            return;
                        }

                        iterate(goal, endpoint, apiKey, model, iteration + 1);
                    } catch (Exception e) {
                        running = false;
                        Log.e(TAG, "LOOP RESULT ERROR", e);
                        callback.reply("I couldn't complete the task safely.");
                    }
                }

                @Override public void onError(String message) {
                    running = false;
                    Log.e(TAG, "LOOP AI ERROR: " + message);
                    callback.reply("My AI core is unavailable while working on that task.");
                }
            });
        } catch (Exception e) {
            running = false;
            Log.e(TAG, "LOOP ERROR", e);
            callback.reply("I couldn't start the agent loop.");
        }
    }

    private String safe(String value) { return value == null || value.trim().isEmpty() ? "None available." : value; }
    public void stop() { running = false; }
}
