package com.aircontrol;

import org.json.JSONObject;

/** Adapts the Jarvis OS operation envelope to NOVA's existing action layer. */
public final class NovaJarvisOperationAdapter implements NovaDeviceGateway.OperationHandler {
    private final NovaActionEngine actions;

    public NovaJarvisOperationAdapter(NovaActionEngine actions) {
        this.actions = actions;
    }

    @Override public JSONObject execute(JSONObject operation) {
        JSONObject result = new JSONObject();
        try {
            String type = operation.optString("type", "").trim().toLowerCase();
            boolean ok;
            switch (type) {
                case "home":
                case "back":
                case "recents":
                case "notifications":
                case "quick_settings":
                case "scroll_up":
                case "scroll_down":
                case "swipe_left":
                case "swipe_right":
                case "open_url":
                case "open_package":
                case "open_app":
                case "search":
                case "settings":
                    ok = actions.execute(type, operation.optString("value", operation.optString("packageName", "")));
                    break;
                case "wait":
                    ok = actions.execute("wait", String.valueOf(operation.optLong("durationMs", 500L)));
                    break;
                case "done":
                    ok = true;
                    break;
                default:
                    ok = false;
                    result.put("error", "Operation is not yet implemented by NOVA: " + type);
            }
            result.put("ok", ok);
            if (!ok && !result.has("error")) result.put("error", "NOVA could not execute: " + type);
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", e.getMessage() == null ? "Operation failed" : e.getMessage());
            } catch (Exception ignored) { }
        }
        return result;
    }
}
