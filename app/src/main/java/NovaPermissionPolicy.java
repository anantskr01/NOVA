package com.aircontrol;

/** Central policy boundary for NOVA tool execution. */
public final class NovaPermissionPolicy {
    public enum Risk { LOW, CONFIRMATION_REQUIRED, HIGH }
    private NovaPermissionPolicy() { }

    public static Risk classify(String type) {
        String action = type == null ? "" : type.trim().toLowerCase();
        switch (action) {
            case "remember": case "memory_search": case "read_screen": case "screen_observe":
            case "web_search": case "web_fetch": case "web_research": case "none": case "wait": case "parallel":
            case "pc_observe": case "pc_list_dir": case "pc_search_text": case "pc_read_file":
            case "pc_git_status": case "pc_git_diff":
                return Risk.LOW;
            case "pc_write_file": case "pc_build": case "pc_run":
                return Risk.CONFIRMATION_REQUIRED;
            case "open_url": case "search": case "open_app": case "open_package": case "type_text":
            case "press_enter": case "click_text": case "click_index": case "home": case "back":
            case "recents": case "notifications": case "quick_settings": case "scroll_up": case "scroll_down":
            case "swipe_left": case "swipe_right": case "settings":
                return Risk.LOW;
            default:
                return Risk.HIGH;
        }
    }

    public static String check(String type) {
        Risk risk = classify(type);
        if (risk == Risk.HIGH) return "high_risk_tool_not_enabled:" + (type == null ? "" : type.trim());
        return "";
    }
}
