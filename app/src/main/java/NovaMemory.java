package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/** Persistent, user-controlled NOVA memory stored locally on the tablet. */
public final class NovaMemory {
    private static final String PREFS = "nova_memory";
    private static final String HISTORY = "history";
    private static final String FACTS = "facts";
    private static final int MAX_MESSAGES = 14;
    private static final int MAX_FACTS = 30;
    private static final int MAX_TEXT_CHARS = 2000;

    private final SharedPreferences prefs;

    public NovaMemory(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void remember(String role, String text) {
        if (text == null || text.trim().isEmpty() || looksSensitive(text)) return;
        String safeText = limit(text.trim(), MAX_TEXT_CHARS);
        JSONArray history = read();
        JSONObject item = new JSONObject();
        try {
            item.put("role", role == null ? "user" : role);
            item.put("content", safeText);
            history.put(item);
        } catch (JSONException ignored) { }
        while (history.length() > MAX_MESSAGES) history.remove(0);
        prefs.edit().putString(HISTORY, history.toString()).apply();
    }

    public synchronized void rememberFact(String key, String value) {
        if (key == null || key.trim().isEmpty() || value == null || value.trim().isEmpty()) return;
        String safeKey = key.trim();
        String safeValue = value.trim();
        if (looksSensitive(safeKey) || looksSensitive(safeValue)) return;

        JSONArray facts = readFacts();
        boolean replaced = false;
        for (int i = 0; i < facts.length(); i++) {
            JSONObject item = facts.optJSONObject(i);
            if (item != null && safeKey.equalsIgnoreCase(item.optString("key"))) {
                try { item.put("value", limit(safeValue, 1000)); } catch (JSONException ignored) { }
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            JSONObject item = new JSONObject();
            try {
                item.put("key", safeKey);
                item.put("value", limit(safeValue, 1000));
                facts.put(item);
            } catch (JSONException ignored) { }
        }
        while (facts.length() > MAX_FACTS) facts.remove(0);
        prefs.edit().putString(FACTS, facts.toString()).apply();
    }

    public synchronized String factsSummary() {
        JSONArray facts = readFacts();
        if (facts.length() == 0) return "No saved facts.";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject item = facts.optJSONObject(i);
            if (item == null) continue;
            if (out.length() > 1800) break;
            out.append(item.optString("key"))
                    .append(": ")
                    .append(item.optString("value"))
                    .append('\n');
        }
        return out.toString().trim();
    }

    public synchronized JSONArray recent() { return read(); }

    public synchronized void clear() {
        prefs.edit().remove(HISTORY).remove(FACTS).apply();
    }

    private JSONArray read() {
        String raw = prefs.getString(HISTORY, "[]");
        try { return new JSONArray(raw); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private JSONArray readFacts() {
        String raw = prefs.getString(FACTS, "[]");
        try { return new JSONArray(raw); }
        catch (JSONException e) { return new JSONArray(); }
    }

    private boolean looksSensitive(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.contains("api key") || lower.contains("apikey") || lower.contains("password")
                || lower.contains("passcode") || lower.contains("secret") || lower.contains("token")
                || lower.contains("private key") || lower.contains("bearer ");
    }

    private String limit(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
