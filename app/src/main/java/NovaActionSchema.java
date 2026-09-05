package com.aircontrol;

import org.json.JSONObject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Canonical schema and validation rules for NOVA agent actions. */
public final class NovaActionSchema {
    private NovaActionSchema() { }

    public static final int MAX_ACTION_VALUE_CHARS = 4096;
    public static final Set<String> TYPES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "home", "back", "recents", "notifications", "quick_settings",
            "scroll_up", "scroll_down", "swipe_left", "swipe_right",
            "open_url", "open_package", "open_app", "click_text", "click_index",
            "type_text", "press_enter", "search", "read_screen", "screen_observe",
            "web_search", "web_fetch", "web_research", "memory_search", "remember",
            "parallel", "settings", "wait", "none"
    )));

    private static final Set<String> UI_MUTATIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "home", "back", "recents", "notifications", "quick_settings",
            "scroll_up", "scroll_down", "swipe_left", "swipe_right",
            "open_url", "open_package", "open_app", "click_text", "click_index",
            "type_text", "press_enter", "search", "settings"
    )));

    private static final Set<String> INFORMATIONAL = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "web_search", "web_fetch", "web_research", "screen_observe",
            "read_screen", "memory_search"
    )));

    /** Memory writes and all Android UI actions are mutations; they are never parallel-safe. */
    public static boolean isKnown(String type) { return type != null && TYPES.contains(type.trim().toLowerCase(Locale.ROOT)); }
    public static boolean isUiMutation(String type) { return type != null && UI_MUTATIONS.contains(type.trim().toLowerCase(Locale.ROOT)); }
    public static boolean isInformational(String type) { return type != null && INFORMATIONAL.contains(type.trim().toLowerCase(Locale.ROOT)); }
    public static boolean isMutation(String type) { return isUiMutation(type) || "remember".equals(normalizeType(type)); }
    public static boolean canRunInParallel(String type) { return isInformational(type); }

    public static String validate(JSONObject action) {
        if (action == null) return "action_missing";
        String type = normalizeType(action.optString("type", ""));
        if (!isKnown(type)) return "unknown_action:" + type;
        if ("none".equals(type)) return "";
        String value = action.optString("value", "").trim();
        if (requiresNonEmptyValue(type) && value.isEmpty()) return "value_empty:" + type;
        if (value.length() > MAX_ACTION_VALUE_CHARS) return "value_too_long:" + type;
        if ("click_index".equals(type)) {
            try { if (Integer.parseInt(value) < 1) return "invalid_index:" + value; }
            catch (NumberFormatException e) { return "invalid_index:" + value; }
        }
        if ("wait".equals(type)) {
            try { long ms = Long.parseLong(value); if (ms < 100 || ms > 2500) return "wait_out_of_range:" + ms; }
            catch (NumberFormatException e) { return "invalid_wait:" + value; }
        }
        if ("open_url".equals(type)) {
            String lower = value.toLowerCase(Locale.ROOT);
            if (!(lower.startsWith("http://") || lower.startsWith("https://"))) return "invalid_url_scheme";
        }
        if ("remember".equals(type)) {
            try {
                JSONObject memory = new JSONObject(value);
                if (memory.optString("key", "").trim().isEmpty()) return "remember_key_empty";
                if (memory.optString("value", "").trim().isEmpty()) return "remember_value_empty";
                if (memory.optString("key").length() > 512 || memory.optString("value").length() > 2048) return "remember_value_too_long";
            } catch (Exception e) { return "remember_invalid_json"; }
        }
        if ("parallel".equals(type)) {
            try {
                org.json.JSONArray steps = new org.json.JSONArray(value);
                if (steps.length() == 0 || steps.length() > NovaAgentPolicy.MAX_STEPS) return "parallel_step_limit";
                for (int i = 0; i < steps.length(); i++) {
                    JSONObject step = steps.optJSONObject(i);
                    if (step == null) return "parallel_invalid_step:" + i;
                    String nested = validate(step);
                    if (!nested.isEmpty()) return "parallel_invalid_step:" + i + ":" + nested;
                    if (!canRunInParallel(step.optString("type", ""))) return "parallel_mutation_forbidden:" + i;
                }
            } catch (Exception e) { return "parallel_invalid_json"; }
        }
        return "";
    }

    public static String normalizeType(String type) {
        return type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean requiresNonEmptyValue(String type) {
        switch (type) {
            case "open_url": case "open_package": case "open_app": case "click_text": case "click_index":
            case "type_text": case "search": case "web_search": case "web_fetch": case "web_research":
            case "memory_search": case "remember": case "parallel": case "wait": return true;
            default: return false;
        }
    }
}
