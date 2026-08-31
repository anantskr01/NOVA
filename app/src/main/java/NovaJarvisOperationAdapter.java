package com.aircontrol;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Path;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONObject;

/**
 * Maps the useful subset of Jarvis OS daemon operations onto NOVA's existing
 * Accessibility and action layers. Unsupported operations fail explicitly so
 * the remote agent cannot mistake a partial implementation for success.
 */
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
                case "tap_element":
                    ok = service() != null && service().clickVisibleIndex(operation.optInt("elementId", -1));
                    break;
                case "tap_coordinates":
                    ok = service() != null && service().tapCoordinates(
                            (float) operation.optDouble("x", Double.NaN),
                            (float) operation.optDouble("y", Double.NaN));
                    break;
                case "type_text":
                    ok = service() != null && service().typeText(operation.optString("text", ""));
                    if (ok && operation.optBoolean("submit", false)) {
                        ok = service().pressEnter();
                    }
                    break;
                case "swipe":
                    ok = service() != null && service().swipeCoordinates(
                            (float) operation.optDouble("x1", Double.NaN),
                            (float) operation.optDouble("y1", Double.NaN),
                            (float) operation.optDouble("x2", Double.NaN),
                            (float) operation.optDouble("y2", Double.NaN),
                            Math.max(50L, Math.min(3000L, operation.optLong("durationMs", 300L))));
                    break;
                case "press_key":
                    ok = pressKey(operation.optString("key", ""));
                    break;
                case "open_app":
                    ok = actions.execute("open_app", operation.optString("packageName", ""));
                    break;
                case "wait":
                    SystemClock.sleep(Math.max(0L, Math.min(10000L, operation.optLong("durationMs", 1000L))));
                    ok = true;
                    break;
                case "done":
                    ok = true;
                    break;
                case "home": case "back": case "recents": case "notifications":
                    ok = actions.execute(type, "");
                    break;
                default:
                    ok = actions.execute(type, operation.optString("value", ""));
                    break;
            }
            result.put("ok", ok);
            if (!ok) result.put("error", "NOVA could not execute operation: " + type);
        } catch (Exception e) {
            try {
                result.put("ok", false);
                result.put("error", e.getMessage() == null ? "Operation failed" : e.getMessage());
            } catch (Exception ignored) { }
        }
        return result;
    }

    private GestureAccessibilityService service() {
        return GestureAccessibilityService.getInstance();
    }

    private boolean pressKey(String key) {
        if (service() == null) return false;
        switch (key.toLowerCase()) {
            case "back": return service().performGlobalActionPublic(AccessibilityService.GLOBAL_ACTION_BACK);
            case "home": return service().performGlobalActionPublic(AccessibilityService.GLOBAL_ACTION_HOME);
            case "recents": return service().performGlobalActionPublic(AccessibilityService.GLOBAL_ACTION_RECENTS);
            case "notifications": return service().performGlobalActionPublic(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS);
            case "enter": return service().pressEnter();
            default: return false;
        }
    }
}
