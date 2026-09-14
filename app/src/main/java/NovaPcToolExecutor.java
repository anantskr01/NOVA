package com.aircontrol;

import org.json.JSONObject;

/** Bridges planner PC tools to the authenticated PC companion and preserves verification evidence. */
public final class NovaPcToolExecutor {
    private final NovaPcAgent agent;

    public NovaPcToolExecutor(NovaPcAgent agent) {
        this.agent = agent;
    }

    public boolean isConnected() {
        return agent != null && agent.isConnected();
    }

    public String observe() {
        return agent == null ? "pc_agent_unavailable" : agent.observe();
    }

    public NovaToolResult execute(String tool, String value) {
        if (agent == null) return NovaToolResult.failure(tool, "pc_agent_unavailable", false);
        try {
            JSONObject args = value == null || value.trim().isEmpty()
                    ? new JSONObject()
                    : new JSONObject(value);
            return agent.execute(new NovaToolInput(tool, value == null ? "" : value, args));
        } catch (Exception e) {
            return NovaToolResult.failure(tool, "invalid_pc_arguments:" + e.getMessage(), false);
        }
    }
}
