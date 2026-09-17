package com.aircontrol;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicLong;

/** Priority-aware front-end scheduler for Brain goals and managed external agents. */
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

    public interface ExternalTask {
        void start(ExternalListener listener);
        void cancel();
    }

    public interface ExternalListener {
        void onStatus(String text);
        void onFinished(boolean success, String summary);
    }

    public static final class Task {
        private final String id;
        private final String goal;
        private final int priority;
        private final long sequence;
        private final long createdAt;
        private final ExternalTask external;
        private long startedAt;
        private long finishedAt;
        private String status = QUEUED;
        private Task(String id, String goal, int priority, long sequence, ExternalTask external) {
            this.id = id; this.goal = goal; this.priority = priority; this.sequence = sequence;
            this.createdAt = System.currentTimeMillis(); this.external = external;
        }
        public String id() { return id; }
        public String goal() { return goal; }
        public int priority() { return priority; }
        public String status() { return status; }
    }

    private final NovaBrain brain;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PriorityQueue<Task> queue = new PriorityQueue<>(
            Comparator.<Task>comparingInt(Task::priority).reversed().thenComparingLong(t -> t.sequence));
    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
    private Task active;
    private boolean shutdown;
    private final Runnable pump = new Runnable() {
        @Override public void run() {
            synchronized (NovaTaskManager.this) {
                if (shutdown) return;
                pumpLocked();
                if (!shutdown) main.postDelayed(this, 350L);
            }
        }
    };

    public NovaTaskManager(NovaBrain brain) {
        if (brain == null) throw new IllegalArgumentException("brain == null");
        this.brain = brain;
        brain.setGoalListener(this::onBrainGoalFinished);
        main.post(pump);
    }

    public synchronized String submit(String goal, int priority) {
        return submitInternal(goal, priority, null);
    }

    public synchronized String submitExternal(String goal, int priority, ExternalTask external) {
        if (external == null) return "";
        return submitInternal(goal, priority, external);
    }

    private String submitInternal(String goal, int priority, ExternalTask external) {
        if (shutdown || goal == null || goal.trim().isEmpty()) return "";
        pruneFinished();
        if (tasks.size() >= MAX_TRACKED) return "";
        int p = Math.max(0, Math.min(priority, 10));
        long sequence = NEXT_ID.getAndIncrement();
        String id = "NOVA-T" + String.format("%04d", sequence);
        Task task = new Task(id, goal.trim(), p, sequence, external);
        tasks.put(id, task);
        queue.offer(task);
        pumpLocked();
        return id;
    }

    private void pumpLocked() {
        if (shutdown || brain == null) return;
        if (active != null) {
            if (active.startedAt > 0 && System.currentTimeMillis() - active.startedAt > NovaAgentPolicy.MAX_TASK_MILLIS + 5_000L) {
                Task timedOut = active;
                timedOut.status = FAILED;
                timedOut.finishedAt = System.currentTimeMillis();
                active = null;
                if (timedOut.external != null) {
                    try { timedOut.external.cancel(); } catch (Exception ignored) { }
                } else {
                    brain.cancelAllGoals();
                }
                status("TASK " + timedOut.id + " • TIMEOUT");
            } else if (active.external != null) {
                return;
            } else if (brain.isBusy()) {
                return;
            } else {
                return;
            }
        }
        if (brain.isBusy()) return;
        Task next = queue.poll();
        if (next == null) return;
        active = next;
        next.status = RUNNING;
        next.startedAt = System.currentTimeMillis();
        if (next.external != null) {
            try {
                next.external.start(new ExternalListener() {
                    @Override public void onStatus(String text) {
                        if (text != null && !text.trim().isEmpty()) status("TASK " + next.id + " • " + text);
                    }
                    @Override public void onFinished(boolean success, String summary) {
                        main.post(() -> onExternalTaskFinished(next, success, summary));
                    }
                });
            } catch (Exception e) {
                onExternalTaskFinished(next, false, "External task failed to start: " + e.getMessage());
            }
        } else {
            brain.think(next.goal);
        }
    }

    private void onExternalTaskFinished(Task task, boolean success, String summary) {
        synchronized (this) {
            if (shutdown || active != task || !RUNNING.equals(task.status)) return;
            task.status = success ? COMPLETED : FAILED;
            task.finishedAt = System.currentTimeMillis();
            active = null;
            status("TASK " + task.id + " • " + (success ? "COMPLETED" : "FAILED"));
            if (summary != null && !summary.trim().isEmpty()) status(summary.trim());
            pumpLocked();
        }
    }

    /** Receives the authoritative terminal outcome from NovaBrain for the current task. */
    public synchronized void onBrainGoalFinished(String goal, NovaBrain.GoalOutcome outcome) {
        if (shutdown || active == null || active.external != null || goal == null || !goal.equals(active.goal)) return;
        if (outcome == NovaBrain.GoalOutcome.SUCCESS) active.status = COMPLETED;
        else if (outcome == NovaBrain.GoalOutcome.FAILED) active.status = FAILED;
        else active.status = CANCELLED;
        active.finishedAt = System.currentTimeMillis();
        active = null;
        pumpLocked();
    }

    public synchronized boolean cancel(String id) {
        Task task = tasks.get(normalizeId(id));
        if (task == null || COMPLETED.equals(task.status) || FAILED.equals(task.status) || CANCELLED.equals(task.status)) return false;
        if (task == active) {
            task.status = CANCELLED;
            task.finishedAt = System.currentTimeMillis();
            active = null;
            if (task.external != null) {
                try { task.external.cancel(); } catch (Exception ignored) { }
            } else {
                brain.cancelAllGoals();
            }
            pumpLocked();
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
            if (!COMPLETED.equals(task.status) && !FAILED.equals(task.status) && !CANCELLED.equals(task.status)) {
                task.status = CANCELLED;
                task.finishedAt = now;
                count++;
            }
        }
        queue.clear();
        Task running = active;
        active = null;
        if (running != null && running.external != null) {
            try { running.external.cancel(); } catch (Exception ignored) { }
        }
        if (brain != null) brain.cancelAllGoals();
        return count;
    }

    public synchronized Task active() { return active; }
    public synchronized int queuedCount() { return queue.size(); }
    public synchronized String activeText() {
        if (active == null || !RUNNING.equals(active.status)) return "NOVA is idle.";
        return "NOVA is running " + active.id + ": " + active.goal;
    }
    public synchronized String statusText() {
        if (tasks.isEmpty()) return "NOVA has no tracked tasks.";
        StringBuilder out = new StringBuilder("NOVA TASKS\n");
        for (Task task : tasks.values()) out.append(task.id).append(" • ").append(task.status.toUpperCase()).append(" • P").append(task.priority).append(" • ").append(task.goal).append('\n');
        return out.toString().trim();
    }
    public synchronized JSONArray snapshot() {
        JSONArray out = new JSONArray();
        for (Task task : tasks.values()) {
            try {
                out.put(new JSONObject().put("id", task.id).put("goal", task.goal).put("priority", task.priority)
                        .put("status", task.status).put("createdAt", task.createdAt).put("startedAt", task.startedAt).put("finishedAt", task.finishedAt));
            } catch (Exception ignored) { }
        }
        return out;
    }
    public synchronized void shutdown() {
        shutdown = true;
        main.removeCallbacks(pump);
        queue.clear();
        Task running = active;
        if (running != null && RUNNING.equals(running.status)) {
            running.status = CANCELLED;
            running.finishedAt = System.currentTimeMillis();
            if (running.external != null) {
                try { running.external.cancel(); } catch (Exception ignored) { }
            }
        }
        active = null;
        if (brain != null) brain.cancelAllGoals();
    }
    private String normalizeId(String id) { return id == null ? "" : id.trim().toUpperCase(); }
    private void pruneFinished() {
        if (tasks.size() < MAX_TRACKED) return;
        Iterator<Map.Entry<String, Task>> it = tasks.entrySet().iterator();
        while (it.hasNext() && tasks.size() >= MAX_TRACKED) {
            Task task = it.next().getValue();
            if (COMPLETED.equals(task.status) || FAILED.equals(task.status) || CANCELLED.equals(task.status)) it.remove();
        }
    }
    private void status(String text) { System.out.println("NOVA • " + text); }
}
