package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real bounded coding loop: inspect workspace -> ask the configured model for one edit -> apply it -> run verification.
 * It never executes arbitrary shell text; verification is delegated to the PC companion allowlist.
 */
public final class NovaCodingAgent {
    public interface Listener { void onStatus(String text); void onFinished(boolean success, String summary); }
    private static final int MAX_ITERATIONS = 4;
    private final NovaAiProvider provider;
    private final String endpoint;
    private final String apiKey;
    private final String model;
    private final NovaPcAgent pc;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public NovaCodingAgent(NovaAiProvider provider, String endpoint, String apiKey, String model, NovaPcAgent pc) {
        if (provider == null || pc == null) throw new IllegalArgumentException("coding agent requires AI provider and PC agent");
        this.provider = provider; this.endpoint = endpoint; this.apiKey = apiKey; this.model = model; this.pc = pc;
    }

    public void start(String goal, Listener listener) {
        executor.execute(() -> run(goal == null ? "" : goal.trim(), listener));
    }

    private void run(String goal, Listener listener) {
        if (goal.isEmpty()) { finish(listener, false, "Coding goal is empty."); return; }
        try {
            String workspace = pc.listFiles(".");
            String build = safeRead("build.gradle");
            String settings = safeRead("settings.gradle");
            String prompt = buildPrompt(goal, workspace, build, settings);
            for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
                status(listener, "CODING AGENT • ITERATION " + iteration + "/" + MAX_ITERATIONS);
                final Holder holder = new Holder();
                JSONArray messages = new JSONArray().put(new JSONObject().put("role", "system").put("content", systemPrompt()))
                        .put(new JSONObject().put("role", "user").put("content", prompt));
                provider.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
                    @Override public void onResult(String text) { holder.value = text; synchronized (holder) { holder.done = true; holder.notifyAll(); } }
                    @Override public void onError(String message) { holder.error = message; synchronized (holder) { holder.done = true; holder.notifyAll(); } }
                });
                synchronized (holder) { while (!holder.done) holder.wait(45_000L); }
                if (holder.error != null) { finish(listener, false, "AI error: " + holder.error); return; }
                JSONObject plan = parsePlan(holder.value);
                String action = plan.optString("action", "");
                if ("finish".equals(action)) { finish(listener, plan.optBoolean("success", false), plan.optString("summary", "Coding agent finished.")); return; }
                if (!"write".equals(action)) { finish(listener, false, "Coding agent returned an invalid edit plan."); return; }
                String path = plan.optString("path", ""); String content = plan.optString("content", "");
                if (path.isEmpty() || path.startsWith("/") || path.contains("..")) { finish(listener, false, "Blocked unsafe workspace path."); return; }
                pc.writeFile(path, content);
                status(listener, "CODING AGENT • UPDATED " + path);
                NovaPcAgent.ProcessResult check = pc.runAllowed("git diff --check");
                if (check.exitCode() != 0) { prompt = feedback(goal, path, check.output()); continue; }
                check = pc.runAllowed("gradlew.bat test");
                if (check.exitCode() == 0) { finish(listener, true, "Implemented and verified changes for: " + goal); return; }
                prompt = feedback(goal, path, check.output());
            }
            finish(listener, false, "Coding agent reached its bounded iteration limit without verification success.");
        } catch (Exception e) { finish(listener, false, "Coding agent failed: " + e.getMessage()); }
    }

    private String safeRead(String path) { try { return pc.readFile(path); } catch (Exception ignored) { return "<not present>"; } }
    private static String buildPrompt(String goal, String workspace, String build, String settings) {
        return "Goal: " + goal + "\nWorkspace listing:\n" + workspace + "\nRoot build.gradle:\n" + build + "\nsettings.gradle:\n" + settings + "\nInspect before editing. Return exactly one JSON object: {\\\"action\\\":\\\"write\\\",\\\"path\\\":\\\"relative/path\\\",\\\"content\\\":\\\"complete file content\\\"} or {\\\"action\\\":\\\"finish\\\",\\\"success\\\":true/false,\\\"summary\\\":\\\"...\\\"}. Never invent APIs or modify outside the workspace.";
    }
    private static String feedback(String goal, String path, String output) { return "Goal: " + goal + "\nPrevious file: " + path + "\nVerification failed. Output:\n" + output + "\nReturn one corrected JSON edit or finish=false. Preserve existing behavior and make the smallest complete fix."; }
    private static String systemPrompt() { return "You are NOVA's coding agent. Work like a careful senior engineer. You may make one complete file replacement per iteration. Do not output markdown. Do not add secrets, credential collection, malware, persistence, surveillance, destructive commands, or arbitrary shell execution. Prefer minimal compatible changes. Verification is performed separately."; }
    private static JSONObject parsePlan(String raw) throws Exception {
        String text = raw == null ? "" : raw.trim(); int start = text.indexOf('{'); int end = text.lastIndexOf('}'); if (start < 0 || end <= start) throw new IOException("AI did not return JSON"); return new JSONObject(text.substring(start, end + 1));
    }
    private void status(Listener l, String s) { if (l != null) l.onStatus(s); }
    private void finish(Listener l, boolean ok, String summary) { if (l != null) l.onFinished(ok, summary); }
    public void shutdown() { executor.shutdownNow(); }
    private static final class Holder { boolean done; String value; String error; }
}
