package com.aircontrol;

import org.json.JSONObject;

/** Bridges planner PC tools to the authenticated PC companion and preserves verification evidence. */
public final class NovaPcToolExecutor {
    private final NovaPcAgent agent;
    private final NovaPermissionGate permissionGate;

    public NovaPcToolExecutor(NovaPcAgent agent) {
        this(agent, NovaPermissionGate.DENY_BY_DEFAULT);
    }

    public NovaPcToolExecutor(NovaPcAgent agent, NovaPermissionGate gate) {
        this.agent = agent;
        this.permissionGate = gate == null ? NovaPermissionGate.DENY_BY_DEFAULT : gate;
    }

    public boolean isConnected() {
        return agent != null && agent.isConnected();
    }

    public String observe() {
        return agent == null ? "pc_agent_unavailable" : agent.observe();
    }

    public NovaToolResult execute(String tool, String value) {
        if (agent == null) return NovaToolResult.failure(tool, "pc_agent_unavailable", "PC agent unavailable", false);
        String action = tool == null ? "" : tool.trim().toLowerCase();
        NovaPermissionPolicy.Risk risk = NovaPermissionPolicy.classify(action);
        if (risk == NovaPermissionPolicy.Risk.HIGH) {
            return NovaToolResult.failure(action, "high_risk_tool_not_enabled", "High-risk PC tool is not enabled", false);
        }
        if (risk == NovaPermissionPolicy.Risk.CONFIRMATION_REQUIRED
                && !permissionGate.approve(action, value == null ? "" : value)) {
            return NovaToolResult.failure(action, "confirmation_required", "User confirmation required", false);
        }
        try {
            JSONObject args = value == null || value.trim().isEmpty()
                    ? new JSONObject()
                    : new JSONObject(value);
            return agent.execute(new NovaToolInput(action, value == null ? "" : value, args));
        } catch (Exception e) {
            return NovaToolResult.failure(action, "invalid_pc_arguments", "invalid_pc_arguments:" + e.getMessage(), false);
        }
    }
}
