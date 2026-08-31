package com.aircontrol;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Builds a small, deterministic context package for NOVA without exposing
 * unbounded history to the model. The engine is local and side-effect free.
 */
public final class NovaContextEngine {
    private static final int MAX_HISTORY_MESSAGES = 8;
    private static final int MAX_MESSAGE_CHARS = 900;
    private static final int MAX_FACT_CHARS = 1800;

    private final NovaMemory memory;

    public NovaContextEngine(Context context) {
        memory = new NovaMemory(context.getApplicationContext());
    }

    public JSONArray recentMessages() {
        JSONArray source = memory.recent();
        JSONArray result = new JSONArray();
        int start = Math.max(0, source.length() - MAX_HISTORY_MESSAGES);
        for (int i = start; i < source.length(); i++) {
            JSONObject item = source.optJSONObject(i);
            if (item == null) continue;
            JSONObject copy = new JSONObject();
            copy.put("role", item.optString("role", "user"));
            copy.put("content", trim(item.optString("content", ""), MAX_MESSAGE_CHARS));
            result.put(copy);
        }
        return result;
    }

    public String relevantMemory(String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        String facts = memory.recall(q);
        if (facts == null || facts.trim().isEmpty()) facts = memory.factsSummary();
        return trim(facts, MAX_FACT_CHARS);
    }

    public JSONObject build(String currentCommand) {
        JSONObject context = new JSONObject();
        context.put("command", trim(currentCommand, MAX_MESSAGE_CHARS));
        context.put("recent_messages", recentMessages());
        context.put("relevant_memory", relevantMemory(currentCommand));
        return context;
    }

    private String trim(String value, int max) {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }
}
