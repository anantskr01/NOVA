package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit state/evidence tracker for a multi-turn PC coding task. */
public final class NovaCodingTaskState {
    public enum Phase { INSPECT, PLAN, MODIFY, VERIFY_WRITE, REVIEW_DIFF, BUILD, TEST, DIAGNOSE, RECOVER, VERIFY, COMPLETE, FAILED }

    private final long startedAt = System.currentTimeMillis();
    private Phase phase = Phase.INSPECT;
    private int turns;
    private int mutations;
    private int builds;
    private int tests;
    private int failures;
    private int recoveries;
    private String lastTool = "";
    private String lastError = "";
    private String lastEvidence = "";
    private final Set<String> inspectedFiles = new LinkedHashSet<>();
    private final Set<String> modifiedFiles = new LinkedHashSet<>();

    public synchronized void turn() { turns++; }

    public synchronized void observeTool(String tool, String value, String result, boolean verified) {
        lastTool = tool == null ? "" : tool;
        lastEvidence = NovaAgentPolicy.bounded(result == null ? "" : result, NovaAgentPolicy.MAX_TOOL_RESULT_CHARS);
        String path = value == null ? "" : value.trim();
        if ("pc_search_text".equals(tool)) phase = Phase.INSPECT;
        else if ("pc_read_file".equals(tool)) { if (!path.isEmpty()) inspectedFiles.add(path); phase = Phase.PLAN; }
        else if ("pc_write_file".equals(tool)) { mutations++; if (!path.isEmpty()) modifiedFiles.add(path); phase = verified ? Phase.VERIFY_WRITE : Phase.RECOVER; }
        else if ("pc_git_diff".equals(tool)) phase = verified ? Phase.REVIEW_DIFF : Phase.RECOVER;
        else if ("pc_build".equals(tool)) { builds++; phase = verified ? Phase.BUILD : Phase.DIAGNOSE; }
        else if ("pc_run".equals(tool)) { tests++; phase = verified ? Phase.TEST : Phase.DIAGNOSE; }
        else if ("pc_git_status".equals(tool) || "pc_observe".equals(tool)) phase = Phase.INSPECT;
        if (!verified) { failures++; lastError = extractError(result); phase = Phase.DIAGNOSE; }
    }

    public synchronized void recover() { recoveries++; phase = Phase.RECOVER; }
    public synchronized void complete() { phase = Phase.COMPLETE; }
    public synchronized void fail(String error) { phase = Phase.FAILED; lastError = error == null ? "" : error; }
    public synchronized boolean isTerminal() { return phase == Phase.COMPLETE || phase == Phase.FAILED; }

    public synchronized JSONObject snapshot() {
        try {
            JSONArray inspected = new JSONArray();
            for (String file : inspectedFiles) inspected.put(file);
            JSONArray modified = new JSONArray();
            for (String file : modifiedFiles) modified.put(file);
            return new JSONObject()
                    .put("phase", phase.name()).put("turns", turns).put("mutations", mutations)
                    .put("builds", builds).put("tests", tests).put("failures", failures)
                    .put("recoveries", recoveries).put("startedAt", startedAt).put("lastTool", lastTool)
                    .put("lastError", lastError).put("lastEvidence", lastEvidence)
                    .put("inspectedFiles", inspected).put("modifiedFiles", modified);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private String extractError(String result) {
        try {
            JSONObject json = new JSONObject(result == null ? "{}" : result);
            return json.optString("error", json.optString("message", "unverified_tool_result"));
        } catch (Exception ignored) {
            return "unverified_tool_result";
        }
    }
}
