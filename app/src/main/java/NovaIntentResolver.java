package com.aircontrol;

import java.util.Locale;

/** Lightweight local intent classifier used before invoking the remote/local model. */
public final class NovaIntentResolver {
    public enum Intent { CONVERSATION, DEVICE_ACTION, MULTI_STEP, MEMORY, SCREEN_QUERY }

    public Intent resolve(String command) {
        if (command == null || command.trim().isEmpty()) return Intent.CONVERSATION;
        String c = command.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("remember ") || c.contains("what do you remember") || c.contains("what do you know about me")) {
            return Intent.MEMORY;
        }
        if (c.contains("what is on my screen") || c.contains("what's on my screen") ||
                c.contains("whats on my screen") || c.contains("read my screen") ||
                c.contains("describe my screen")) return Intent.SCREEN_QUERY;
        String[] starts = {"open ", "launch ", "send ", "message ", "call ", "tap ", "click ",
                "type ", "play ", "pause ", "search ", "find ", "create ", "delete ", "share ", "email "};
        boolean action = false;
        for (String s : starts) if (c.startsWith(s)) { action = true; break; }
        if (action && (c.contains(" and ") || c.contains(" then ") || c.contains(" after "))) return Intent.MULTI_STEP;
        if (action) return Intent.DEVICE_ACTION;
        return Intent.CONVERSATION;
    }
}
