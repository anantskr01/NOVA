package com.aircontrol;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.UUID;

/** Coordinates user-level tasks with the NovaBrain goal lifecycle. */
public final class NovaTaskManager {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED }

    public static final class Task {
        public final String id;
        public final String goal;
        public final long createdAt;
        public long startedAt;
        public long finishedAt;
        public Status status;

        Task(String goal) {
            this.id = UUID.randomUUID().toString();
            this.goal = goal;
            this.createdAt = System.currentTimeMillis();
            this.status = Status.QUEUED;
        }
    }

    private final NovaBrain brain;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Queue<Task> queue = new ArrayDeque<>();
    private Task active;
    private boolean shutdown;

    private final Runnable timeoutGuard = new Runnable() {
        @Override public void run() {
            synchronized (NovaTaskManager.this) {
                if (shutdown || active == null) return;
                long elapsed = System.currentTimeMillis() - active.startedAt;
                if (elapsed > NovaAgentPolicy.MAX_TASK_MILLIS + 5_000L) {
                    active.status = Status.FAILED;
                    active.finishedAt = System.currentTimeMillis();
                    active = null;
                    brain.cancelAllGoals();
                    pumpLocked();
                    return;
                }
                handler.postDelayed(this, 1_000L);
            }
        }
    };

    public NovaTaskManager(NovaBrain brain) {
        if (brain == null) throw new IllegalArgumentException("brain == null");
        this.brain = brain;
        brain.setGoalOutcomeListener(this::onBrainGoalFinished);
    }

    public synchronized Task submit(String goal) {
        if (shutdown) throw new IllegalStateException("task manager is shut down");
        if (goal == null || goal.trim().isEmpty()) throw new IllegalArgumentException("goal is empty");
        Task task = new Task(goal.trim());
        queue.offer(task);
        pumpLocked();
        return task;
    }

    public synchronized Task activeTask() { return active; }
    public synchronized int queuedCount() { return queue.size(); }

    public synchronized void cancelAll() {
        if (shutdown) return;
        for (Task task : queue) {
            task.status = Status.CANCELLED;
            task.finishedAt = System.currentTimeMillis();
        }
        queue.clear();
        if (active != null) {
            active.status = Status.CANCELLED;
            active.finishedAt = System.currentTimeMillis();
            active = null;
        }
        brain.cancelAllGoals();
        handler.removeCallbacks(timeoutGuard);
    }

    public synchronized void shutdown() {
        if (shutdown) return;
        shutdown = true;
        for (Task task : queue) {
            task.status = Status.CANCELLED;
            task.finishedAt = System.currentTimeMillis();
        }
        queue.clear();
        if (active != null) {
            active.status = Status.CANCELLED;
            active.finishedAt = System.currentTimeMillis();
            active = null;
        }
        handler.removeCallbacks(timeoutGuard);
        brain.shutdown();
    }

    /** Called by NovaBrain only after the goal has reached a terminal state. */
    public synchronized void onBrainGoalFinished(String goal, NovaBrain.GoalOutcome outcome) {
        if (shutdown || active == null || goal == null || !goal.equals(active.goal)) return;
        if (outcome == NovaBrain.GoalOutcome.SUCCESS) active.status = Status.COMPLETED;
        else if (outcome == NovaBrain.GoalOutcome.FAILED) active.status = Status.FAILED;
        else active.status = Status.CANCELLED;
        active.finishedAt = System.currentTimeMillis();
        active = null;
        handler.removeCallbacks(timeoutGuard);
        pumpLocked();
    }

    private void pumpLocked() {
        if (shutdown || active != null) return;
        Task next = queue.poll();
        if (next == null) return;
        active = next;
        active.status = Status.RUNNING;
        active.startedAt = System.currentTimeMillis();
        handler.removeCallbacks(timeoutGuard);
        handler.post(timeoutGuard);
        brain.think(active.goal);
    }
}
