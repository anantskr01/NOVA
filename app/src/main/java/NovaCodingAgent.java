package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bounded coding loop: inspect workspace -> read relevant files -> make one complete edit -> verify.
 * It never executes arbitrary shell text; verification is delegated to the PC companion allowlist.
 */
public final class NovaCodingAgent {
    public interface Listener { void onStatus(String text); void onFinished(boolean success, String summary); }

    private static final int MAX_ITERATIONS = 8;
    private static final long MODEL_TIMEOUT_MS = 60_000L;
    private static final int MAX_CONTEXT_CHARS = 120_000;

    private final NovaAiProvider provider;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final NovaPcAgent pc;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public NovaCodingAgent(NovaAiProvider provider, String endpoint, String apiKey, String model, NovaPcAgent pc) {
        if (provider == null || pc == null) throw new IllegalArgumentException("coding agent requires AI provider and PC agent");
        this.provider = provider;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
        this.pc = pc;
    }

    public void start(String goal, Listener listener) {
        executor.execute(() -> run(goal == null ? "" : goal.trim(), listener));
    }

    private void run(String goal, Listener listener) {
        if (goal.isEmpty()) {
            finish(listener, false, "Coding goal is empty.");
            return;
        }
        try {
            status(listener, "CODING AGENT • INSPECTING WORKSPACE");
            String workspace = pc.listFiles(".");
            String build = safeRead("build.gradle");
            String settings = safeRead("settings.gradle");
            String prompt = buildPrompt(goal, workspace, build, settings);

            for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
                status(listener, "CODING AGENT • ITERATION " + iteration + "/" + MAX_ITERATIONS);
                String raw = askModel(prompt);
                JSONObject plan = parsePlan(raw);
                String action = plan.optString("action", "");

                if ("finish".equals(action)) {
                    finish(listener, plan.optBoolean("success", false), plan.optString("summary", "Coding agent finished."));
                    return;
                }

                if ("read".equals(action)) {
                    String path = validatePath(plan.optString("path", ""));
                    String content;
                    try {
                        content = pc.readFile(path);
                    } catch (Exception e) {
                        prompt = feedback(goal, "read", path, "Read failed: " + e.getMessage());
                        continue;
                    }
                    prompt = prompt + "\n\nREAD FILE " + path + ":\n" + limit(content) + "\n\nNow return exactly one JSON action: read another relevant file, write one complete file, or finish.";
                    continue;
                }

                if (!"write".equals(action)) {
                    finish(listener, false, "Coding agent returned an invalid action: " + action);
                    return;
                }

                String path = validatePath(plan.optString("path", ""));
                String content = plan.optString("content", "");
                if (content.isEmpty()) {
                    finish(listener, false, "Coding agent returned an empty file edit.");
                    return;
                }
                pc.writeFile(path, content);
                status(listener, "CODING AGENT • UPDATED " + path);

                NovaPcAgent.ProcessResult check = pc.runAllowed("git diff --check");
                if (check.exitCode() != 0) {
                    prompt = feedback(goal, "diff", path, check.output());
                    continue;
                }

                check = pc.runAllowed("gradlew.bat assembleDebug");
                if (check.exitCode() == 0) {
                    NovaPcAgent.ProcessResult statusCheck = pc.runAllowed("git status --short --branch");
                    finish(listener, true, "Implemented and verified changes for: " + goal + "\n" + statusCheck.output());
                    return;
                }
                prompt = feedback(goal, "build", path, check.output());
            }

            finish(listener, false, "Coding agent reached its bounded iteration limit without verification success.");
        } catch (Exception e) {
            finish(listener, false, "Coding agent failed: " + e.getMessage());
        }
    }

    private String askModel(String prompt) throws Exception {
        final Holder holder = new Holder();
        JSONArray messages = new JSONArray()
                .put(new JSONObject().put("role", "system").put("content", systemPrompt()))
                .put(new JSONObject().put("role", "user").put("content", prompt));
        provider.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
            @Override public void onResult(String text) {
                synchronized (holder) {
                    holder.value = text;
                    holder.done = true;
                    holder.notifyAll();
                }
            }
            @Override public void onError(String message) {
                synchronized (holder) {
                    holder.error = message;
                    holder.done = true;
                    holder.notifyAll();
                }
            }
        });

        long deadline = System.currentTimeMillis() + MODEL_TIMEOUT_MS;
        synchronized (holder) {
            while (!holder.done) {
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) throw new IOException("AI response timed out");
                holder.wait(remaining);
            }
        }
        if (holder.error != null) throw new IOException(holder.error);
        if (holder.value == null || holder.value.trim().isEmpty()) throw new IOException("AI returned an empty response");
        return holder.value;
    }

    private String safeRead(String path) {
        try { return limit(pc.readFile(path)); }
        catch (Exception ignored) { return "<not present>"; }
    }

    private static String buildPrompt(String goal, String workspace, String build, String settings) {
        return "Goal: " + goal
                + "\nWorkspace listing:\n" + limit(workspace)
                + "\nRoot build.gradle:\n" + limit(build)
                + "\nsettings.gradle:\n" + limit(settings)
                + "\nInspect before editing. You can request files with action=read."
                + " Return exactly one JSON object: {\"action\":\"read\",\"path\":\"relative/path\"}"
                + " or {\"action\":\"write\",\"path\":\"relative/path\",\"content\":\"complete file content\"}"
                + " or {\"action\":\"finish\",\"success\":true/false,\"summary\":\"...\"}."
                + " Never invent APIs or modify outside the workspace.";
    }

    private static String feedback(String goal, String phase, String path, String output) {
        return "Goal: " + goal
                + "\nPrevious phase: " + phase
                + "\nPrevious file: " + path
                + "\nVerification failed. Output:\n" + limit(output)
                + "\nReturn exactly one corrected JSON read/write action or finish=false."
                + " Preserve existing behavior and make the smallest complete fix.";
    }

    private static String systemPrompt() {
        return "You are NOVA's coding agent. Work like a careful senior engineer."
                + " You may make one complete file replacement per iteration and may request relevant files before editing."
                + " Do not output markdown. Do not add secrets, credential collection, malware, persistence, surveillance, destructive commands, or arbitrary shell execution."
                + " Prefer minimal compatible changes. Verification is performed separately."
                + " Only use relative workspace paths; never use .. or absolute paths.";
    }

    private static JSONObject parsePlan(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) throw new IOException("AI did not return JSON");
        return new JSONObject(text.substring(start, end + 1));
    }

    private static String validatePath(String path) throws IOException {
        if (path == null || path.isBlank() || path.startsWith("/") || path.startsWith("\\") || path.contains("..") || path.contains(":") || path.contains("\\")) {
            throw new IOException("Blocked unsafe workspace path");
        }
        return path;
    }

    private static String limit(String value) {
        if (value == null) return "";
        if (value.length() <= MAX_CONTEXT_CHARS) return value;
        return value.substring(0, MAX_CONTEXT_CHARS) + "\n[truncated]";
    }

    private void status(Listener l, String s) { if (l != null) l.onStatus(s); }
    private void finish(Listener l, boolean ok, String summary) { if (l != null) l.onFinished(ok, summary); }

    public void shutdown() { executor.shutdownNow(); }

    private static final class Holder {
        boolean done;
        String value;
        String error;
    }
}
