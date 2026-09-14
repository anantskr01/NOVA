package com.aircontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Central registry describing NOVA's executable agent capabilities. */
public final class NovaToolRegistry {
    private final Map<String, NovaTool> tools = new LinkedHashMap<>();

    public NovaToolRegistry() {
        add("home", "Return to Android home", true);
        add("back", "Navigate back", true);
        add("recents", "Open recent apps", true);
        add("notifications", "Open notification shade", true);
        add("quick_settings", "Open quick settings", true);
        add("scroll_up", "Scroll upward", true);
        add("scroll_down", "Scroll downward", true);
        add("swipe_left", "Swipe left", true);
        add("swipe_right", "Swipe right", true);
        add("open_url", "Open an HTTP/HTTPS URL", true);
        add("open_package", "Launch an installed package", true);
        add("open_app", "Launch an installed app by name", true);
        add("click_text", "Activate the best visible UI element matching supplied text or accessibility description", true);
        add("click_index", "Activate a numbered visible UI item when the observed UI provides a reliable index", true);
        add("type_text", "Replace the focused or best visible editable field with text", true);
        add("press_enter", "Submit the focused editable field", true);
        add("search", "Open a web search", true);
        add("read_screen", "Read visible screen text", true);
        add("screen_observe", "Observe the current Android UI tree", true);
        add("web_search", "Search the public web and return structured results", true);
        add("web_fetch", "Fetch a public page and return bounded text", true);
        add("web_research", "Start a bounded public-web research pass", true);
        add("memory_search", "Search saved NOVA facts", true);
        add("remember", "Save a durable fact explicitly provided by the user", true);
        add("parallel", "Run independent informational tools concurrently", true);
        add("settings", "Open Android settings", true);
        add("wait", "Wait for a bounded duration", true);
        add("pc_observe", "Observe the authenticated PC companion state", true);
        add("pc_list_dir", "List files/directories inside the authenticated PC workspace", true);
        add("pc_search_text", "Search source files in the authenticated PC workspace for a literal text pattern", true);
        add("pc_read_file", "Read a bounded file inside the authenticated PC workspace", true);
        add("pc_write_file", "Write and verify a file inside the authenticated PC workspace", false);
        add("pc_git_status", "Inspect Git status in the authenticated PC workspace", true);
        add("pc_git_diff", "Inspect the current Git diff in the authenticated PC workspace", true);
        add("pc_build", "Run an allow-listed build tool in the authenticated PC workspace", false);
        add("pc_run", "Run an allow-listed development command in the authenticated PC workspace", false);
        add("none", "Do nothing", true);
    }

    private void add(String type, String description, boolean reversible) {
        register(new BasicTool(type, description, reversible));
    }

    public synchronized void register(NovaTool tool) {
        if (tool == null || tool.type() == null || tool.type().trim().isEmpty()) return;
        tools.put(tool.type().trim().toLowerCase(), tool);
    }

    public synchronized boolean contains(String type) {
        return type != null && tools.containsKey(type.trim().toLowerCase());
    }

    public synchronized NovaTool get(String type) {
        return type == null ? null : tools.get(type.trim().toLowerCase());
    }

    public synchronized List<NovaTool> all() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    /** Planner-facing capability manifest: schema + risk + reversibility, not just descriptions. */
    public synchronized String promptSummary() {
        StringBuilder out = new StringBuilder();
        for (NovaTool t : tools.values()) {
            if (out.length() > 10000) break;
            out.append("- ").append(t.type())
                    .append(": ").append(t.description())
                    .append("; schema=").append(t.parameterSchema())
                    .append("; risk=").append(t.risk())
                    .append("; reversible=").append(t.reversible())
                    .append("; parallel=").append(t.supportsParallel())
                    .append('\n');
        }
        out.append("\nCODING AGENT RULES: For coding requests, behave as an engineering agent, not a chat assistant. Follow INSPECT -> PLAN -> MODIFY -> BUILD -> DIAGNOSE -> FIX -> REBUILD -> VERIFY. First inspect the workspace and search for relevant symbols/files before editing. Read the smallest relevant files needed to understand the code. Before every mutation, produce a concrete intended change; pc_write_file is confirmation-gated. After every write, inspect Git diff and build. Treat non-zero build/test output as evidence to diagnose, never as success. Read compiler/runtime errors, identify the root cause, make the smallest corrective edit, rebuild, and repeat. Do not claim a coding task is complete until the final build/test result is successful and the Git diff matches the requested change. Never overwrite unrelated work. Prefer targeted source edits and preserve existing behavior outside the requested change.\n");
        return out.toString().trim();
    }

    private static final class BasicTool implements NovaTool {
        private final String type;
        private final String description;
        private final boolean reversible;

        BasicTool(String t, String d, boolean r) {
            type = t;
            description = d;
            reversible = r;
        }

        public String type() { return type; }
        public String description() { return description; }
        public boolean reversible() { return reversible; }
    }
}
