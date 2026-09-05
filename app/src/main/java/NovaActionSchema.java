package com.aircontrol;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Canonical schema and validation rules for NOVA agent actions. */
public final class NovaActionSchema {
    private NovaActionSchema() { }

    public static final Set<String> TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "home", "back", "recents", "notifications", "quick_settings",
            "scroll_up", "scroll_down", "swipe_left", "swipe_right",
            "open_url", "open_package", "open_app", "click_text", "click_index",
            "type_text", "press_enter", "search", "read_screen", "screen_observe",
            "web_search", "web_fetch", "web_research", "memory_search", "remember",
            "calculate", "parallel", "settings", "wait", "none"
    )));

    private static final Set<String> UI_MUTATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "home", "back", "recents", "notifications", "quick_settings",
            "scroll_up", "scroll_down", "swipe_left", "swipe_right",
            "open_url", "open_package", "open_app", "click_text", "click_index",
            "type_text", "press_enter", "search", "settings"
    )));

    private static final Set<String> INFORMATIONAL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "web_search", "web_fetch", "web_research", "screen_observe",
            "read_screen", "memory_search", "remember", "calculate"
    )));

    public static boolean isKnown(String type) {
        return type != null && TYPES.contains(type.trim().toLowerCase());
    }
    public static boolean isUiMutation(String type) {
        return type != null && UI_MUTATIONS.contains(type.trim().toLowerCase());
    }
    public static boolean isInformational(String type) {
        return type != null && INFORMATIONAL.contains(type.trim().toLowerCase());
    }
    public static boolean canRunInParallel(String type) { return isInformational(type); }

    /** Returns an empty string when valid, otherwise a compact validation error. */
    public static String validate(JSONObject action) {
        if (action == null) return "action_missing";
        String type = action.optString("type", "").trim().toLowerCase();
        if (!isKnown(type)) return "unknown_action:" + type;
        if ("none".equals(type)) return "";
        String value = action.optString("value", "").trim();
        if (requiresNonEmptyValue(type) && value.isEmpty()) return "value_empty:" + type;
        if ("click_index".equals(type)) {
            try { if (Integer.parseInt(value) < 1) return "invalid_index:" + value; }
            catch (NumberFormatException e) { return "invalid_index:" + value; }
        }
        if ("wait".equals(type)) {
            try {
                long ms = Long.parseLong(value);
                if (ms < 100 || ms > 2500) return "wait_out_of_range:" + ms;
            } catch (NumberFormatException e) { return "invalid_wait:" + value; }
        }
        if ("open_url".equals(type)) {
            String lower = value.toLowerCase();
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return "invalid_url_scheme";
        }
        return "";
    }

    private static boolean requiresNonEmptyValue(String type) {
        switch (type) {
            case "open_url": case "open_package": case "open_app":
            case "click_text": case "click_index": case "type_text":
            case "search": case "web_search": case "web_fetch":
            case "web_research": case "memory_search": case "remember":
            case "calculate": case "parallel": case "wait":
                return true;
            default: return false;
        }
    }
}
