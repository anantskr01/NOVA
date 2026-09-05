package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Lightweight task-management facade over NOVA Brain. Keeps user-visible task identity and status. */
public final class NovaTaskManager {
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String CANCELLED = "cancelled";

    private static final int MAX_TRACKED = 24;

    private static final class Task {
        final String id;
        final String goal;
        final int priority;
        String status;
        final long createdAt;
        long startedAt;
        long finishedAt;

        Task(String id, String goal, int priority) {
            this.id = id;
            this.goal = goal;
            this.priority = priority;
            this.status = QUEUED;
            this.createdAt = System.currentTimeMillis();
        }
    }

    private final NovaBrain brain;
    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();

    public NovaTaskManager(NovaBrain brain) {
        this.brain = brain;
    }

    public synchronized String submit(String goal, int priority) {
        if (brain == null || goal == null || goal.trim().isEmpty()) return "";
        if (tasks.size() >= MAX_TRACKED) pruneFinished();
        if (tasks.size() >= MAX_TRACKED) return "";

        String id = "NOVA-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Task task = new Task(id, goal.trim(), Math.max(0, Math.min(priority, 10)));
        tasks.put(id, task);
        brain.think(task.goal);
        syncLocked();
        return id;
    }

    public synchronized void sync() {
        syncLocked();
    }

    private void syncLocked() {
        if (tasks.isEmpty()) return;
        String active = brain == null ? "" : brain.activeGoal();
        boolean busy = brain != null && brain.isBusy();

        for (Task task : tasks.values()) {
            if (task.status.equals(CANCELLED) || task.status.equals(COMPLETED)) continue;
            if (busy && !active.isEmpty() && task.goal.equals(active)) {
                if (!task.status.equals(RUNNING)) task.startedAt = System.currentTimeMillis();
                task.status = RUNNING;
            } else if (task.status.equals(RUNNING)) {
                task.status = COMPLETED;
                task.finishedAt = System.currentTimeMillis();
            }
        }
    }

    public synchronized boolean cancel(String id) {
        syncLocked();
        Task task = tasks.get(id == null ? "" : id.trim().toUpperCase());
        if (task == null || task.status.equals(COMPLETED) || task.status.equals(CANCELLED)) return false;
        task.status = CANCELLED;
        task.finishedAt = System.currentTimeMillis();
        if (brain != null) brain.cancelAllGoals();
        return true;
    }

    public synchronized void cancelAll() {
        if (brain != null) brain.cancelAllGoals();
        long now = System.currentTimeMillis();
        for (Task task : tasks.values()) {
            if (!task.status.equals(COMPLETED)) {
                task.status = CANCELLED;
                task.finishedAt = now;
            }
        }
    }

    public synchronized String statusText() {
        syncLocked();
        if (tasks.isEmpty()) return "NOVA has no tracked tasks.";
        StringBuilder out = new StringBuilder("NOVA TASKS\n");
        for (Task task : tasks.values()) {
            out.append(task.id).append(" • ")
                    .append(task.status.toUpperCase()).append(" • P")
                    .append(task.priority).append(" • ")
                    .append(task.goal).append('\n');
        }
        return out.toString().trim();
    }

    public synchronized String activeText() {
        syncLocked();
        if (brain == null || !brain.isBusy()) return "NOVA is idle.";
        String active = brain.activeGoal();
        return active == null || active.isEmpty() ? "NOVA is running a task." : "NOVA is running: " + active;
    }

    public synchronized JSONArray snapshot() {
        syncLocked();
        JSONArray out = new JSONArray();
        for (Task task : tasks.values()) {
            try {
                out.put(new JSONObject()
                        .put("id", task.id)
                        .put("goal", task.goal)
                        .put("priority", task.priority)
                        .put("status", task.status)
                        .put("createdAt", task.createdAt)
                        .put("startedAt", task.startedAt)
                        .put("finishedAt", task.finishedAt));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private void pruneFinished() {
        java.util.Iterator<Map.Entry<String, Task>> it = tasks.entrySet().iterator();
        while (it.hasNext() && tasks.size() >= MAX_TRACKED) {
            Task task = it.next().getValue();
            if (COMPLETED.equals(task.status) || CANCELLED.equals(task.status)) it.remove();
        }
    }
}
