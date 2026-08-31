package com.aircontrol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central capability registry for NOVA.
 *
 * The registry defines what the agent may request, validates the basic tool
 * envelope, and records whether a side effect requires explicit confirmation.
 * Execution remains owned by the appropriate executor.
 */
public final class NovaToolRegistry {
    public static final int MAX_VALUE_LENGTH = 4096;

    public enum Kind { ANDROID, SCREEN, WEB, MEMORY, APPS, FILES, COMMUNICATION, CONTROL }

    public static final class Tool {
        public final String type;
        public final String description;
        public final Kind kind;
        public final boolean requiresConfirmation;
        public final boolean requiresValue;

        private Tool(String type, String description, Kind kind,
                     boolean requiresConfirmation, boolean requiresValue) {
            this.type = type;
            this.description = description;
            this.kind = kind;
            this.requiresConfirmation = requiresConfirmation;
            this.requiresValue = requiresValue;
        }
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public NovaToolRegistry() {
        register("home", "Go to the Android home screen", Kind.ANDROID, false, false);
        register("back", "Navigate back", Kind.ANDROID, false, false);
        register("recents", "Open recent apps", Kind.ANDROID, false, false);
        register("notifications", "Open the notification shade", Kind.ANDROID, false, false);
        register("quick_settings", "Open Android quick settings", Kind.ANDROID, false, false);
        register("scroll_up", "Scroll upward", Kind.ANDROID, false, false);
        register("scroll_down", "Scroll downward", Kind.ANDROID, false, false);
        register("swipe_left", "Swipe left", Kind.ANDROID, false, false);
        register("swipe_right", "Swipe right", Kind.ANDROID, false, false);
        register("open_url", "Open a web URL", Kind.ANDROID, false, true);
        register("open_package", "Open an installed Android package", Kind.ANDROID, false, true);
        register("open_app", "Open an installed application by name", Kind.APPS, false, true);
        register("settings", "Open Android settings", Kind.ANDROID, false, false);

        register("click_text", "Activate visible accessibility text", Kind.SCREEN, false, true);
        register("click_index", "Activate a numbered visible accessibility item", Kind.SCREEN, false, true);
        register("click_coordinates", "Tap screen coordinates using a bounded gesture", Kind.SCREEN, false, true);
        register("long_click_text", "Long-press visible accessibility text", Kind.SCREEN, false, true);
        register("type_text", "Enter text into a visible editable field", Kind.SCREEN, false, true);
        register("read_screen", "Inspect the currently visible accessibility UI", Kind.SCREEN, false, false);
        register("verify_screen_contains", "Verify visible UI contains expected text", Kind.SCREEN, false, true);
        register("wait", "Wait briefly for UI state to settle", Kind.CONTROL, false, false);
        register("none", "No operation", Kind.CONTROL, false, false);

        register("search", "Perform a web search", Kind.WEB, false, true);
        register("web.search", "Search the internet", Kind.WEB, false, true);
        register("web.open", "Open a web page", Kind.WEB, false, true);
        register("web.fetch", "Fetch readable web content", Kind.WEB, false, true);

        register("memory.remember", "Store a local memory item", Kind.MEMORY, false, true);
        register("memory.recall", "Recall local memory", Kind.MEMORY, false, false);
        register("apps.list", "List installed launchable applications", Kind.APPS, false, false);

        register("files.read", "Read an allowed local NOVA workspace file", Kind.FILES, false, true);
        register("files.write", "Write an allowed local NOVA workspace file", Kind.FILES, false, true);
        register("files.create", "Create an allowed local NOVA workspace file", Kind.FILES, false, true);
        register("code.create", "Create source code in the allowed NOVA workspace", Kind.FILES, false, true);
        register("code.modify", "Modify source code in the allowed NOVA workspace", Kind.FILES, false, true);

        register("communication.send_message", "Send an external message on the user's behalf", Kind.COMMUNICATION, true, true);
        register("communication.make_call", "Start an external phone call", Kind.COMMUNICATION, true, true);
    }

    private void register(String type, String description, Kind kind,
                          boolean confirmation, boolean requiresValue) {
        tools.put(type.toLowerCase(Locale.ROOT),
                new Tool(type, description, kind, confirmation, requiresValue));
    }

    public boolean contains(String type) {
        return type != null && tools.containsKey(normalize(type));
    }

    public Tool get(String type) {
        return type == null ? null : tools.get(normalize(type));
    }

    /** Validate the complete tool envelope before any executor is called. */
    public boolean validate(String type, String value) {
        Tool tool = get(type);
        if (tool == null) return false;
        if (value != null && value.length() > MAX_VALUE_LENGTH) return false;
        return !tool.requiresValue || (value != null && !value.trim().isEmpty());
    }

    public boolean requiresConfirmation(String type) {
        Tool tool = get(type);
        return tool != null && tool.requiresConfirmation;
    }

    public boolean requiresValue(String type) {
        Tool tool = get(type);
        return tool != null && tool.requiresValue;
    }

    public Kind kind(String type) {
        Tool tool = get(type);
        return tool == null ? null : tool.kind;
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tools.keySet()));
    }

    /** Stable, compact capability description for the agent prompt. */
    public String promptSummary() {
        StringBuilder out = new StringBuilder();
        for (Tool tool : tools.values()) {
            out.append("- ").append(tool.type)
                    .append(" [").append(tool.kind.name().toLowerCase(Locale.ROOT)).append("]")
                    .append(": ").append(tool.description);
            if (tool.requiresValue) out.append(" {value required}");
            if (tool.requiresConfirmation) out.append(" [CONFIRMATION REQUIRED]");
            out.append('\n');
        }
        return out.toString().trim();
    }

    private String normalize(String type) {
        return type.trim().toLowerCase(Locale.ROOT);
    }
}
