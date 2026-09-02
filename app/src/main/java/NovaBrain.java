package com.aircontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Central NOVA reasoning layer. Owns open-ended goals and coordinates
 * context -> model -> plan -> execution -> verification -> one recovery/re-plan.
 */
public final class NovaBrain {
    private static final String TAG = "NovaBrain";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final int MAX_HISTORY = 14;
    private static final int MAX_QUEUE = 6;
    private static final int MAX_RECOVERY_ATTEMPTS = 1;

    public interface Listener {
        void onStatus(String text);
        void onReply(String text);
    }

    private final Context context;
    private final Listener listener;
    private final NovaMemory memory;
    private final NovaSecureStore secureStore;
    private final NovaAiClient ai = new NovaAiClient();
    private final NovaActionEngine actions;
    private final NovaAgentPlanner planner;
    private final NovaToolRegistry tools;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<String> queue = new ArrayDeque<>();
    private boolean processing;
    private boolean shutdown;
    private long generation;
    private String activeGoal = "";

    public NovaBrain(Context context, NovaActionEngine actions, NovaMemory memory, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.actions = actions;
        this.memory = memory == null ? new NovaMemory(this.context) : memory;
        this.secureStore = new NovaSecureStore(this.context);
        this.tools = new NovaToolRegistry();

        planner = new NovaAgentPlanner(new NovaAgentPlanner.ActionExecutor() {
            @Override public boolean execute(String type, String value) {
                return NovaBrain.this.actions != null && NovaBrain.this.actions.execute(type, value);
            }
            @Override public String readScreen() {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service == null ? "Accessibility service is not connected." : service.getVisibleTextSummary();
            }
            @Override public boolean clickText(String text) {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service != null && service.clickText(text);
            }
            @Override public boolean clickVisibleIndex(int index) {
                GestureAccessibilityService service = GestureAccessibilityService.getInstance();
                return service != null && service.clickVisibleIndex(index);
            }
        }, new NovaAgentPlanner.Listener() {
            @Override public void status(String text) { NovaBrain.this.status(text); }
            @Override public void reply(String text) { NovaBrain.this.reply(text); }
        }, tools);
    }

    /** Queue an open-ended goal. Android UI actions remain serialized for safety. */
    public synchronized void think(String request) {
        if (shutdown || request == null || request.trim().isEmpty()) return;
        if (getEndpoint().isEmpty()) {
            reply("My AI core isn't configured yet.");
            return;
        }
        if (queue.size() >= MAX_QUEUE) {
            reply("My task queue is full. Cancel or finish a task before adding another.");
            return;
        }
        queue.offer(request.trim());
        status(processing ? "BRAIN • GOAL QUEUED • " + queue.size() : "BRAIN • GOAL ACCEPTED");
        processNextLocked();
    }

    /** Cancel queued and in-flight NOVA work. In-flight callbacks become stale and cannot execute a plan. */
    public synchronized void cancelAllGoals() {
        generation++;
        queue.clear();
        activeGoal = "";
        processing = false;
        status("BRAIN • TASKS CANCELLED");
    }

    /** Backward-compatible queue cancellation entry point. */
    public synchronized void cancelQueuedGoals() { cancelAllGoals(); }

    public synchronized int queuedCount() { return queue.size(); }
    public synchronized boolean isBusy() { return processing; }
    public synchronized String activeGoal() { return activeGoal; }

    private void processNextLocked() {
        if (processing || shutdown) return;
        activeGoal = queue.poll();
        if (activeGoal == null) return;
        processing = true;
        memory.remember("user", activeGoal);
        final long token = generation;
        askAi(activeGoal, 0, "", token);
    }

    private void askAi(final String goal, final int recoveryAttempt, final String failureContext, final long token) {
        synchronized (this) {
            if (shutdown || token != generation) return;
        }
        status(recoveryAttempt == 0 ? "BRAIN • UNDERSTANDING → PLANNING" : "BRAIN • RECOVERING → REPLANNING");
        try {
            JSONArray messages = new JSONArray();

            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", buildSystemPrompt(recoveryAttempt > 0));
            messages.put(system);

            JSONObject contextMessage = new JSONObject();
            contextMessage.put("role", "system");
            String screen = getUiSnapshot();
            contextMessage.put("content", "Saved NOVA memory:\n" + memory.factsSummary()
                    + "\n\nCurrent UI:\n" + screen
                    + (failureContext.isEmpty() ? "" : "\n\nPrevious attempt failure:\n" + failureContext));
            messages.put(contextMessage);

            JSONArray history = memory.recent();
            int start = Math.max(0, history.length() - MAX_HISTORY);
            for (int i = start; i < history.length(); i++) {
                JSONObject item = history.optJSONObject(i);
                if (item != null) messages.put(item);
            }

            ai.chat(getEndpoint(), secureStore.getApiKey(), getModel(), messages, new NovaAiClient.Callback() {
                @Override public void onResult(String text) {
                    synchronized (NovaBrain.this) {
                        if (shutdown || token != generation) return;
                    }
                    NovaAgentPlanner.ExecutionResult result = planner.executeDetailed(text);
                    synchronized (NovaBrain.this) {
                        if (shutdown || token != generation) return;
                    }
                    if (result.completed) {
                        if (!result.say.isEmpty()) rememberAndReply(result.say);
                        finishGoal(token);
                        return;
                    }

                    if (recoveryAttempt < MAX_RECOVERY_ATTEMPTS) {
                        String failure = "Failed action: " + result.failedAction
                                + "\nObserved screen after failure:\n" + result.finalScreen;
                        main.post(() -> askAi(goal, recoveryAttempt + 1, failure, token));
                    } else {
                        String message = result.failedAction.isEmpty()
                                ? "I couldn't complete that task after a recovery attempt."
                                : "I couldn't complete the task after a recovery attempt at: " + result.failedAction + ".";
                        rememberAndReply(message);
                        finishGoal(token);
                    }
                }

                @Override public void onError(String message) {
                    synchronized (NovaBrain.this) {
                        if (shutdown || token != generation) return;
                    }
                    rememberAndReply("My AI core is unavailable right now. " + message);
                    finishGoal(token);
                }
            });
        } catch (Exception e) {
            synchronized (this) {
                if (shutdown || token != generation) return;
            }
            Log.e(TAG, "AI REQUEST PREPARATION ERROR", e);
            rememberAndReply("I couldn't prepare the AI request.");
            finishGoal(token);
        }
    }

    private String buildSystemPrompt(boolean recovery) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are NOVA, a careful general-purpose Android agent. ");
        prompt.append("Understand the user's goal, choose available tools, and return JSON only. ");
        prompt.append("Plan concrete steps, use observation when UI state matters, and never pretend an action succeeded. ");
        prompt.append("Use at most 8 actions per plan. Do not invent tools or action types. ");
        prompt.append("Allowed action schema: {\"say\":\"short natural response\",\"actions\":[{\"type\":\"tool type\",\"value\":\"optional value\"}]}.");
        prompt.append("\nAvailable tools:\n").append(tools.promptSummary());
        prompt.append("\nFor ambiguous UI tasks, read_screen before targeting an element. ");
        prompt.append("Use click_index only when a numbered visible result is explicitly identified. ");
        prompt.append("Never bypass Android permissions, authentication, security controls, or private data boundaries. ");
        prompt.append("Prefer reversible actions and stop when required permission is unavailable.");
        if (recovery) prompt.append("\nThis is a recovery attempt. Inspect the failure context and produce a different, safer plan; do not blindly repeat the failed action.");
        return prompt.toString();
    }

    private void finishGoal(long token) {
        synchronized (this) {
            if (shutdown || token != generation) return;
            processing = false;
            activeGoal = "";
            if (!queue.isEmpty()) {
                status("BRAIN • NEXT GOAL");
                processNextLocked();
            } else {
                status("BRAIN • IDLE");
            }
        }
    }

    private String getEndpoint() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ENDPOINT, "").trim();
    }

    private String getModel() {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(MODEL, "gpt-4o-mini").trim();
    }

    private String getUiSnapshot() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        return service == null ? "Accessibility service is not connected." : service.getUiSnapshot();
    }

    private void rememberAndReply(String text) {
        if (text == null || text.trim().isEmpty()) return;
        memory.remember("assistant", text.trim());
        reply(text.trim());
    }

    private void status(String text) {
        if (listener != null && text != null) listener.onStatus(text);
    }

    private void reply(String text) {
        if (listener != null && text != null && !text.trim().isEmpty()) listener.onReply(text.trim());
    }

    public synchronized void shutdown() {
        shutdown = true;
        generation++;
        queue.clear();
        activeGoal = "";
        processing = false;
        main.removeCallbacksAndMessages(null);
        ai.shutdown();
    }
}
