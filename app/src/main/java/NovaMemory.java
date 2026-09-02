package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Persistent, user-controlled NOVA memory stored locally on the tablet. */
public final class NovaMemory {
    private static final String PREFS = "nova_memory";
    private static final String HISTORY = "history";
    private static final String FACTS = "facts";
    private static final int MAX_MESSAGES = 24;
    private static final int MAX_FACTS = 100;

    private final SharedPreferences prefs;

    public NovaMemory(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void remember(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        JSONArray history = read();
        JSONObject item = new JSONObject();
        try {
            item.put("role", role == null ? "user" : role);
            item.put("content", text.trim());
            item.put("timestamp", System.currentTimeMillis());
            history.put(item);
        } catch (JSONException ignored) { }
        while (history.length() > MAX_MESSAGES) history.remove(0);
        prefs.edit().putString(HISTORY, history.toString()).apply();
    }

    public synchronized void rememberFact(String key, String value) {
        if (!valid(key, value)) return;
        JSONArray facts = readFacts();
        boolean replaced = false;
        String normalized = normalize(key);
        for (int i = 0; i < facts.length(); i++) {
            JSONObject item = facts.optJSONObject(i);
            if (item != null && normalized.equals(normalize(item.optString("key")))) {
                try {
                    item.put("key", normalized);
                    item.put("value", value.trim());
                    item.put("updatedAt", System.currentTimeMillis());
                } catch (JSONException ignored) { }
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            JSONObject item = new JSONObject();
            try {
                item.put("key", normalized);
                item.put("value", value.trim());
                item.put("updatedAt", System.currentTimeMillis());
                facts.put(item);
            } catch (JSONException ignored) { }
        }
        while (facts.length() > MAX_FACTS) facts.remove(0);
        prefs.edit().putString(FACTS, facts.toString()).apply();
    }

    /** Search only relevant stored facts instead of sending the entire memory to the model. */
    public synchronized JSONArray searchFacts(String query, int limit) {
        JSONArray result = new JSONArray();
        if (query == null || query.trim().isEmpty()) return result;
        String[] terms = query.toLowerCase(Locale.ROOT).split("\\s+");
        List<ScoredFact> scored = new ArrayList<>();
        JSONArray facts = readFacts();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i);
            if (fact == null) continue;
            String text = (fact.optString("key", "") + " " + fact.optString("value", "")).toLowerCase(Locale.ROOT);
            int score = 0;
            for (String term : terms) if (term.length() > 1 && text.contains(term)) score++;
            if (score > 0) scored.add(new ScoredFact(fact, score));
        }
        scored.sort((a, b) -> Integer.compare(b.score, a.score));
        int max = Math.min(Math.max(1, limit <= 0 ? 5 : limit), scored.size());
        for (int i = 0; i < max; i++) result.put(scored.get(i).fact);
        return result;
    }

    public synchronized String factsSummary() {
        JSONArray facts = readFacts();
        if (facts.length() == 0) return "No saved facts.";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject item = facts.optJSONObject(i);
            if (item == null) continue;
            if (out.length() > 4000) break;
            out.append(item.optString("key"))
                    .append(": ")
                    .append(item.optString("value"))
                    .append('\n');
        }
        return out.toString().trim();
    }

    public synchronized JSONArray recent() { return read(); }

    public synchronized void clear() { prefs.edit().remove(HISTORY).remove(FACTS).apply(); }

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

    private static boolean valid(String key, String value) {
        return key != null && !key.trim().isEmpty() && value != null && !value.trim().isEmpty();
    }

    private static String normalize(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private static final class ScoredFact {
        final JSONObject fact;
        final int score;
        ScoredFact(JSONObject fact, int score) { this.fact = fact; this.score = score; }
    }
}
