package com.aircontrol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
        add("click_index", "Activate a numbered visible UI item only when the current observation provides a reliable index", true);
        add("type_text", "Replace the focused or best visible editable field with text", true);
        add("press_enter", "Submit the focused editable field", true);
        add("search", "Open a web search", true);
        add("read_screen", "Read visible screen text", true);
        add("screen_observe", "Observe the current Android UI tree", true);
        add("web_search", "Search the public web and return structured results", true);
        add("web_fetch", "Fetch a public page and return bounded text", true);
        add("web_research", "Run a bounded public-web research pass", true);
        add("memory_search", "Search saved NOVA facts", true);
        add("remember", "Save a durable fact explicitly provided by the user", true);
        add("parallel", "Run independent informational tools concurrently", true);
        add("settings", "Open Android settings", true);
        add("wait", "Wait for a bounded duration", true);
        add("none", "Do nothing", true);
    }

    private void add(String type, String description, boolean reversible) { register(new BasicTool(type, description, reversible)); }
    public synchronized void register(NovaTool tool) { if (tool != null && tool.type() != null && !tool.type().trim().isEmpty()) tools.put(tool.type().trim().toLowerCase(Locale.ROOT), tool); }
    public synchronized boolean contains(String type) { return type != null && tools.containsKey(type.trim().toLowerCase(Locale.ROOT)); }
    public synchronized NovaTool get(String type) { return type == null ? null : tools.get(type.trim().toLowerCase(Locale.ROOT)); }
    public synchronized List<NovaTool> all() { return Collections.unmodifiableList(new ArrayList<>(tools.values())); }
    /** Compact registry metadata for planning; execution still requires schema/policy validation. */
    public synchronized String promptSummary() {
        StringBuilder out = new StringBuilder();
        for (NovaTool t : tools.values()) {
            if (out.length() > 7000) break;
            out.append("- ").append(t.type()).append(": ").append(t.description())
                    .append("; category=").append(t.category())
                    .append("; readOnly=").append(t.readOnly())
                    .append("; reversible=").append(t.reversible())
                    .append("; confirmation=").append(t.confirmationRequired())
                    .append("; capability=").append(t.capabilityRequirement())
                    .append("; timeoutMs=").append(t.timeoutMillis())
                    .append("; input=").append(t.inputSchema()).append('\n');
        }
        return out.toString().trim();
    }
    private static final class BasicTool implements NovaTool {
        private final String type, description; private final boolean reversible;
        BasicTool(String t, String d, boolean r) { type = t; description = d; reversible = r; }
        public String type() { return type; }
        public String description() { return description; }
        public boolean reversible() { return reversible; }
    }
}
