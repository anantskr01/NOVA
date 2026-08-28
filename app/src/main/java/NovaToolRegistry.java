package com.aircontrol;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Central registry for NOVA capabilities.
 *
 * The registry is deliberately separate from execution: it describes what
 * the agent may request while NovaActionEngine and other tool implementations
 * remain responsible for actually performing the operation.
 */
public final class NovaToolRegistry {

    public static final int MAX_VALUE_LENGTH = 4096;

    public static final class Tool {
        public final String type;
        public final String description;
        public final boolean requiresConfirmation;

        private Tool(String type, String description, boolean requiresConfirmation) {
            this.type = type;
            this.description = description;
            this.requiresConfirmation = requiresConfirmation;
        }
    }

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public NovaToolRegistry() {
        register("home", "Go to the Android home screen", false);
        register("back", "Navigate back", false);
        register("recents", "Open recent apps", false);
        register("notifications", "Open the notification shade", false);
        register("quick_settings", "Open Android quick settings", false);
        register("scroll_up", "Scroll upward", false);
        register("scroll_down", "Scroll downward", false);
        register("swipe_left", "Swipe left", false);
        register("swipe_right", "Swipe right", false);
        register("open_url", "Open a web URL", false);
        register("open_package", "Open an installed Android package", false);
        register("open_app", "Open an installed application by name", false);
        register("click_text", "Activate visible accessibility text", false);
        register("click_index", "Activate a numbered visible accessibility item", false);
        register("search", "Perform a web search", false);
        register("read_screen", "Inspect the currently visible accessibility UI", false);
        register("settings", "Open Android settings", false);
        register("none", "No operation", false);

        // Phase 2 tool namespaces. These are registered now so the planner has
        // a stable vocabulary; execution is added only when the corresponding
        // implementation exists.
        register("web.search", "Search the internet", false);
        register("web.open", "Open a web page", false);
        register("web.fetch", "Fetch readable web content", false);
        register("memory.remember", "Store a local memory item", false);
        register("memory.recall", "Recall local memory", false);
        register("files.read", "Read an allowed local project file", false);
        register("files.write", "Write an allowed local project file", false);
        register("files.create", "Create an allowed local project file", false);
        register("code.create", "Create source code in an allowed project workspace", false);
        register("code.modify", "Modify source code in an allowed project workspace", false);

        // External communication is intentionally a confirmation-gated future
        // capability. Registration does not grant execution permission.
        register("communication.send_message", "Send an external message on the user's behalf", true);
        register("communication.make_call", "Start an external phone call", true);
    }

    private void register(String type, String description, boolean requiresConfirmation) {
        tools.put(type, new Tool(type, description, requiresConfirmation));
    }

    public boolean contains(String type) {
        return type != null && tools.containsKey(type.trim().toLowerCase(Locale.ROOT));
    }

    public Tool get(String type) {
        if (type == null) return null;
        return tools.get(type.trim().toLowerCase(Locale.ROOT));
    }

    public boolean validate(String type, String value) {
        if (!contains(type)) return false;
        return value == null || value.length() <= MAX_VALUE_LENGTH;
    }

    public boolean requiresConfirmation(String type) {
        Tool tool = get(type);
        return tool != null && tool.requiresConfirmation;
    }

    public Set<String> types() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tools.keySet()));
    }

    public String promptSummary() {
        StringBuilder out = new StringBuilder();
        for (Tool tool : tools.values()) {
            out.append("- ").append(tool.type)
                    .append(": ").append(tool.description);
            if (tool.requiresConfirmation) out.append(" [CONFIRMATION REQUIRED]");
            out.append('\n');
        }
        return out.toString().trim();
    }
}
