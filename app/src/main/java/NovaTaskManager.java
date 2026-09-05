package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Owns NOVA task identity, priority ordering, lifecycle state, and cancellation. */
public final class NovaTaskManager {
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    public static final int PRIORITY_HIGH = 8;
    public static final int PRIORITY_NORMAL = 5;
    public static final int PRIORITY_LOW = 2;
    private static final int MAX_TRACKED = 32;
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    public static final class Task {
        private final String id;
        private final String goal;
        private final int priority;
        private final long sequence;
        private final long createdAt;
        private long startedAt;
        private long finishedAt;
        private String status = QUEUED;
        private String result = "";

        private Task(String id, String goal, int priority, long sequence) {
            this.id = id;
            this.goal = goal;
            this.priority = priority;
            this.sequence = sequence;
            this.createdAt = System.currentTimeMillis();
        }

        public String id() { return id; }
        public String goal() { return goal; }
        public int priority() { return priority; }
        public String status() { return status; }
        public long createdAt() { return createdAt; }
        public long startedAt() { return startedAt; }
        public long finishedAt() { return finishedAt; }
        public String result() { return result; }
    }

    private final PriorityQueue<Task> queue = new PriorityQueue<>(
            Comparator.<Task>comparingInt(Task::priority).reversed()
                    .thenComparingLong(t -> t.sequence));
    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
    private Task active;

    public synchronized Task submit(String goal, int priority) {
        if (goal == null || goal.trim().isEmpty()) return null;
        pruneFinished();
        if (tasks.size() >= MAX_TRACKED) return null;

        int p = Math.max(0, Math.min(priority, 10));
        String id = "NOVA-T" + String.format("%04d", NEXT_ID.getAndIncrement());
        Task task = new Task(id, goal.trim(), p, NEXT_ID.get());
        tasks.put(id, task);
        queue.offer(task);
        return task;
    }

    public synchronized Task pollNext() {
        if (active != null) return null;
        active = queue.poll();
        if (active != null) {
            active.status = RUNNING;
            active.startedAt = System.currentTimeMillis();
        }
        return active;
    }

    public synchronized Task active() { return active; }
    public synchronized int queuedCount() { return queue.size(); }
    public synchronized int trackedCount() { return tasks.size(); }

    public synchronized void complete(Task task, String result) {
        finish(task, COMPLETED, result);
    }

    public synchronized void fail(Task task, String result) {
        finish(task, FAILED, result);
    }

    private void finish(Task task, String state, String result) {
        if (task == null) return;
        task.status = state;
        task.result = result == null ? "" : result.trim();
        task.finishedAt = System.currentTimeMillis();
        if (active == task) active = null;
    }

    public synchronized boolean cancel(String id) {
        Task task = tasks.get(normalizeId(id));
        if (task == null || COMPLETED.equals(task.status) || FAILED.equals(task.status)
                || CANCELLED.equals(task.status)) return false;
        if (active == task) {
            task.status = CANCELLED;
            task.finishedAt = System.currentTimeMillis();
            active = null;
            return true;
        }
        queue.remove(task);
        task.status = CANCELLED;
        task.finishedAt = System.currentTimeMillis();
        return true;
    }

    public synchronized int cancelQueued() {
        int count = 0;
        for (Task task : new ArrayList<>(queue)) {
            queue.remove(task);
            if (QUEUED.equals(task.status)) {
                task.status = CANCELLED;
                task.finishedAt = System.currentTimeMillis();
                count++;
            }
        }
        return count;
    }

    public synchronized int cancelAll() {
        int count = 0;
        long now = System.currentTimeMillis();
        for (Task task : tasks.values()) {
            if (!COMPLETED.equals(task.status) && !FAILED.equals(task.status)
                    && !CANCELLED.equals(task.status)) {
                task.status = CANCELLED;
                task.finishedAt = now;
                count++;
            }
        }
        queue.clear();
        active = null;
        return count;
    }

    public synchronized Task find(String id) {
        return tasks.get(normalizeId(id));
    }

    public synchronized String statusText() {
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

    public synchronized JSONArray snapshot() {
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
                        .put("finishedAt", task.finishedAt)
                        .put("result", task.result));
            } catch (Exception ignored) { }
        }
        return out;
    }

    private String normalizeId(String id) {
        return id == null ? "" : id.trim().toUpperCase();
    }

    private void pruneFinished() {
        if (tasks.size() < MAX_TRACKED) return;
        Iterator<Map.Entry<String, Task>> it = tasks.entrySet().iterator();
        while (it.hasNext() && tasks.size() >= MAX_TRACKED) {
            Task task = it.next().getValue();
            if (COMPLETED.equals(task.status) || FAILED.equals(task.status) || CANCELLED.equals(task.status)) {
                it.remove();
            }
        }
    }
}
