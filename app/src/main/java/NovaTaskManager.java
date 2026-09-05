package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;
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

/** Priority-aware scheduler with persisted lifecycle and bounded resume checkpoints. */
public final class NovaTaskManager implements NovaBrain.GoalOutcomeListener, NovaBrain.GoalProgressListener {
    public static final String QUEUED = "queued";
    public static final String RUNNING = "running";
    public static final String PAUSED = "paused";
    public static final String NEEDS_USER = "needs_user";
    public static final String COMPLETED = "completed";
    public static final String FAILED = "failed";
    public static final String CANCELLED = "cancelled";

    public static final int PRIORITY_HIGH = 8;
    public static final int PRIORITY_NORMAL = 5;
    public static final int PRIORITY_LOW = 2;
    private static final int MAX_TRACKED = 32;
    private static final String PREFS = "nova_task_state";
    private static final String TASKS = "tasks";
    private static final AtomicLong NEXT_ID = new AtomicLong(1);

    public static final class Task {
        private final String id;
        private final String goal;
        private final int priority;
        private final long sequence;
        private final long createdAt;
        private long startedAt;
        private long finishedAt;
        private int progressTurn;
        private String checkpoint = "";
        private String status = QUEUED;

        private Task(String id, String goal, int priority, long sequence) {
            this(id, goal, priority, sequence, System.currentTimeMillis(), 0L, 0L, 0, "", QUEUED);
        }

        private Task(String id, String goal, int priority, long sequence, long createdAt,
                     long startedAt, long finishedAt, int progressTurn, String checkpoint, String status) {
            this.id = id;
            this.goal = goal;
            this.priority = priority;
            this.sequence = sequence;
            this.createdAt = createdAt;
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.progressTurn = progressTurn;
            this.checkpoint = checkpoint == null ? "" : checkpoint;
            this.status = status;
        }
        public String id() { return id; }
        public String goal() { return goal; }
        public int priority() { return priority; }
        public String status() { return status; }
        public int progressTurn() { return progressTurn; }
        public String checkpoint() { return checkpoint; }
    }

    private final Context context;
    private final SharedPreferences prefs;
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
                main.postDelayed(this, 350L);
            }
        }
    };

    public NovaTaskManager(NovaBrain brain) {
        this.brain = brain;
        this.context = null;
        this.prefs = null;
        attachBrain();
        main.post(pump);
    }

    public NovaTaskManager(Context context, NovaBrain brain) {
        this.brain = brain;
        this.context = context == null ? null : context.getApplicationContext();
        this.prefs = this.context == null ? null : this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        attachBrain();
        loadPersisted();
        main.post(pump);
    }

    private void attachBrain() {
        if (brain != null) {
            brain.setGoalOutcomeListener(this);
            brain.setGoalProgressListener(this);
        }
    }

    @Override public synchronized void onGoalProgress(String goal, int turn, String checkpoint) {
        if (shutdown || active == null || !RUNNING.equals(active.status)) return;
        if (goal == null || !active.goal.equals(goal)) return;
        active.progressTurn = Math.max(active.progressTurn, turn);
        active.checkpoint = NovaDiagnostics.compact(checkpoint);
        persistLocked();
        NovaDiagnostics.event("task_progress", active.id + " turn=" + active.progressTurn);
    }

    @Override public void onGoalFinished(String goal, String outcome, String message) {
        synchronized (this) {
            if (shutdown || active == null || !RUNNING.equals(active.status)) return;
            if (goal == null || !active.goal.equals(goal)) return;
            if (NovaBrain.OUTCOME_SUCCESS.equals(outcome)) active.status = COMPLETED;
            else if (NovaBrain.OUTCOME_CANCELLED.equals(outcome)) active.status = CANCELLED;
            else if (NovaBrain.OUTCOME_WAITING_USER.equals(outcome)) active.status = NEEDS_USER;
            else active.status = FAILED;
            if (NEEDS_USER.equals(active.status)) active.finishedAt = 0L;
            else active.finishedAt = System.currentTimeMillis();
            active = null;
            persistLocked();
            if (!shutdown) pumpLocked();
        }
    }

    public synchronized String submit(String goal, int priority) {
        if (shutdown || goal == null || goal.trim().isEmpty()) return "";
        pruneFinished();
        if (tasks.size() >= MAX_TRACKED) return "";
        int p = Math.max(0, Math.min(priority, 10));
        long idNumber = NEXT_ID.getAndIncrement();
        String id = "NOVA-T" + String.format(java.util.Locale.US, "%04d", idNumber);
        Task task = new Task(id, goal.trim(), p, idNumber);
        tasks.put(id, task); queue.offer(task); persistLocked(); pumpLocked();
        return id;
    }

    private void pumpLocked() {
        if (shutdown || brain == null) return;
        if (active != null) {
            if (brain.isBusy()) return;
            return; // Brain outcomes are authoritative; idle is never inferred as success.
        }
        if (brain.isBusy()) return;
        Task next = queue.poll();
        if (next == null || !QUEUED.equals(next.status)) return;
        active = next;
        next.status = RUNNING;
        next.startedAt = System.currentTimeMillis();
        persistLocked();
        String request = resumeRequest(next);
        NovaDiagnostics.event("task_started", next.id + (next.progressTurn > 0 ? " resume=" + next.progressTurn : ""));
        brain.think(request);
    }

    private String resumeRequest(Task task) {
        if (task.progressTurn <= 0 || task.checkpoint.isEmpty()) return task.goal;
        return "Resume the user's task from the current UI state. Original goal: " + task.goal
                + ". NOVA previously verified progress through reasoning turn " + task.progressTurn
                + " (checkpoint: " + task.checkpoint + "). Do not repeat already verified work unless current observation shows it is necessary; re-observe first and continue toward the original goal.";
    }

    public synchronized boolean cancel(String id) {
        Task task = tasks.get(normalizeId(id));
        if (task == null || COMPLETED.equals(task.status) || FAILED.equals(task.status) || CANCELLED.equals(task.status)) return false;
        if (task == active) {
            task.status = CANCELLED; task.finishedAt = System.currentTimeMillis(); active = null;
            if (brain != null) brain.cancelAllGoals(); persistLocked(); pumpLocked(); return true;
        }
        queue.remove(task); task.status = CANCELLED; task.finishedAt = System.currentTimeMillis(); persistLocked(); return true;
    }

    public synchronized boolean pause(String id) {
        Task task = tasks.get(normalizeId(id));
        if (task == null || COMPLETED.equals(task.status) || FAILED.equals(task.status) || CANCELLED.equals(task.status)) return false;
        if (task == active) {
            if (brain != null) brain.cancelAllGoals();
            active = null; task.status = PAUSED; task.finishedAt = 0L; persistLocked(); pumpLocked(); return true;
        }
        if (QUEUED.equals(task.status)) { queue.remove(task); task.status = PAUSED; persistLocked(); return true; }
        return PAUSED.equals(task.status);
    }

    public synchronized boolean resume(String id) {
        Task task = tasks.get(normalizeId(id));
        if (task == null || !PAUSED.equals(task.status)) return false;
        task.status = QUEUED; task.finishedAt = 0L; queue.offer(task); persistLocked(); pumpLocked(); return true;
    }

    public synchronized int cancelQueued() {
        int count = 0;
        for (Task task : new ArrayList<>(queue)) {
            queue.remove(task);
            if (QUEUED.equals(task.status)) { task.status = CANCELLED; task.finishedAt = System.currentTimeMillis(); count++; }
        }
        persistLocked(); return count;
    }

    public synchronized int cancelAll() {
        int count = 0; long now = System.currentTimeMillis();
        for (Task task : tasks.values()) {
            if (!COMPLETED.equals(task.status) && !FAILED.equals(task.status) && !CANCELLED.equals(task.status)) {
                task.status = CANCELLED; task.finishedAt = now; count++;
            }
        }
        queue.clear(); active = null; if (brain != null) brain.cancelAllGoals(); persistLocked(); return count;
    }

    public synchronized Task active() { return active; }
    public synchronized int queuedCount() { return queue.size(); }
    public synchronized String activeText() {
        if (active == null || !RUNNING.equals(active.status)) return "NOVA is idle.";
        return "NOVA is running " + active.id + ": " + active.goal + (active.progressTurn > 0 ? " (checkpoint " + active.progressTurn + ")" : "");
    }

    public synchronized String statusText() {
        if (tasks.isEmpty()) return "NOVA has no tracked tasks.";
        StringBuilder out = new StringBuilder("NOVA TASKS\n");
        for (Task task : tasks.values()) out.append(task.id).append(" • ").append(task.status.toUpperCase())
                .append(" • P").append(task.priority).append(" • ").append(task.goal)
                .append(task.progressTurn > 0 ? " • checkpoint=" + task.progressTurn : "").append('\n');
        return out.toString().trim();
    }

    public synchronized JSONArray snapshot() {
        JSONArray out = new JSONArray();
        for (Task task : tasks.values()) {
            try { out.put(new JSONObject().put("id", task.id).put("goal", task.goal).put("priority", task.priority)
                    .put("status", task.status).put("createdAt", task.createdAt).put("startedAt", task.startedAt)
                    .put("finishedAt", task.finishedAt).put("sequence", task.sequence).put("progressTurn", task.progressTurn)
                    .put("checkpoint", NovaDiagnostics.compact(task.checkpoint))); } catch (Exception ignored) { }
        }
        return out;
    }

    public synchronized void shutdown() { shutdown = true; main.removeCallbacks(pump); persistLocked(); queue.clear(); active = null; }

    private void loadPersisted() {
        if (prefs == null) return;
        try {
            JSONArray saved = new JSONArray(prefs.getString(TASKS, "[]")); long maxSequence = 0L;
            for (int i = 0; i < saved.length(); i++) {
                JSONObject o = saved.optJSONObject(i); if (o == null) continue;
                String id = o.optString("id", "").trim().toUpperCase(); String goal = o.optString("goal", "").trim();
                if (id.isEmpty() || goal.isEmpty()) continue;
                int priority = Math.max(0, Math.min(10, o.optInt("priority", PRIORITY_NORMAL)));
                long sequence = o.optLong("sequence", parseIdNumber(id));
                long created = o.optLong("createdAt", System.currentTimeMillis()); long started = o.optLong("startedAt", 0L); long finished = o.optLong("finishedAt", 0L);
                int progress = Math.max(0, o.optInt("progressTurn", 0)); String checkpoint = NovaDiagnostics.compact(o.optString("checkpoint", ""));
                String status = o.optString("status", QUEUED); if (RUNNING.equals(status)) status = PAUSED;
                if (!QUEUED.equals(status) && !PAUSED.equals(status) && !NEEDS_USER.equals(status) && !COMPLETED.equals(status) && !FAILED.equals(status) && !CANCELLED.equals(status)) status = FAILED;
                Task task = new Task(id, goal, priority, sequence, created, started, finished, progress, checkpoint, status);
                tasks.put(id, task); if (QUEUED.equals(status)) queue.offer(task); maxSequence = Math.max(maxSequence, sequence);
            }
            NEXT_ID.set(Math.max(NEXT_ID.get(), maxSequence + 1));
        } catch (Exception ignored) { }
    }

    private void persistLocked() { if (prefs != null) try { prefs.edit().putString(TASKS, snapshot().toString()).apply(); } catch (Exception ignored) { } }
    private long parseIdNumber(String id) { try { String digits = id.replaceAll("[^0-9]", ""); return digits.isEmpty() ? 0L : Long.parseLong(digits); } catch (Exception ignored) { return 0L; } }
    private String normalizeId(String id) { return id == null ? "" : id.trim().toUpperCase(); }
    private void pruneFinished() {
        if (tasks.size() < MAX_TRACKED) return;
        Iterator<Map.Entry<String, Task>> it = tasks.entrySet().iterator();
        while (it.hasNext() && tasks.size() >= MAX_TRACKED) { Task task = it.next().getValue(); if (COMPLETED.equals(task.status) || FAILED.equals(task.status) || CANCELLED.equals(task.status)) it.remove(); }
        persistLocked();
    }
}
