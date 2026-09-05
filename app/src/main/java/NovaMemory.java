package com.aircontrol;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Persistent, bounded, local-only NOVA memory with explicit memory layers. */
public final class NovaMemory {
    public static final String LAYER_SHORT_TERM = "short_term";
    public static final String LAYER_LONG_TERM = "long_term";
    public static final String LAYER_EPISODIC = "episodic";
    public static final String LAYER_SEMANTIC = "semantic";
    public static final String LAYER_TASK = "task";

    private static final String PREFS = "nova_memory";
    private static final String HISTORY = "history";
    private static final String FACTS = "facts";
    private static final String EPISODES = "episodes";
    private static final String TASKS = "tasks";
    private static final int MAX_MESSAGES = 24;
    private static final int MAX_FACTS = 100;
    private static final int MAX_EPISODES = 40;
    private static final int MAX_TASKS = 32;
    private static final int MAX_FACT_LENGTH = 2048;
    private static final int MAX_EPISODE_LENGTH = 4096;

    private final SharedPreferences prefs;
    public NovaMemory(Context c) { prefs = c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    /** Short-term conversational memory. */
    public synchronized void remember(String role, String text) {
        if (text == null || text.trim().isEmpty()) return;
        String value = bounded(text.trim(), MAX_FACT_LENGTH * 2);
        JSONArray h = read(HISTORY);
        try { h.put(entry(role == null ? "user" : role, value)); } catch (JSONException ignored) { }
        trim(h, MAX_MESSAGES);
        save(HISTORY, h);
    }

    /** Episodic memory records what happened during a task/interaction. */
    public synchronized void rememberEpisode(String taskId, String event, String outcome) {
        if (event == null || event.trim().isEmpty()) return;
        JSONArray a = read(EPISODES);
        try {
            JSONObject item = entry("episode", bounded(event.trim(), MAX_EPISODE_LENGTH));
            item.put("taskId", bounded(taskId, 128));
            item.put("outcome", bounded(outcome, 256));
            a.put(item);
        } catch (JSONException ignored) { }
        trim(a, MAX_EPISODES);
        save(EPISODES, a);
    }

    /** Task memory stores resumable task checkpoints without embedding secrets. */
    public synchronized void rememberTask(String taskId, String goal, String state, String checkpoint) {
        if (taskId == null || taskId.trim().isEmpty() || goal == null || goal.trim().isEmpty()) return;
        JSONArray a = read(TASKS);
        String id = taskId.trim();
        JSONObject existing = null;
        for (int i = 0; i < a.length(); i++) {
            JSONObject item = a.optJSONObject(i);
            if (item != null && id.equals(item.optString("taskId"))) { existing = item; break; }
        }
        try {
            if (existing == null) {
                existing = new JSONObject().put("taskId", bounded(id, 128));
                a.put(existing);
            }
            existing.put("goal", bounded(goal.trim(), MAX_FACT_LENGTH));
            existing.put("state", bounded(state, 256));
            existing.put("checkpoint", bounded(checkpoint, MAX_EPISODE_LENGTH));
            existing.put("updatedAt", System.currentTimeMillis());
        } catch (JSONException ignored) { }
        trim(a, MAX_TASKS);
        save(TASKS, a);
    }

    public synchronized JSONObject taskMemory(String taskId) {
        if (taskId == null) return null;
        JSONArray a = read(TASKS);
        for (int i = 0; i < a.length(); i++) {
            JSONObject item = a.optJSONObject(i);
            if (item != null && taskId.trim().equals(item.optString("taskId"))) return item;
        }
        return null;
    }

    /** Semantic/long-term fact memory. Keys are deduplicated case-insensitively. */
    public synchronized void rememberFact(String key, String value) {
        if (!valid(key, value)) return;
        String normalized = normalize(key), clean = bounded(value.trim(), MAX_FACT_LENGTH);
        JSONArray facts = read(FACTS);
        boolean replaced = false;
        for (int i = 0; i < facts.length(); i++) {
            JSONObject item = facts.optJSONObject(i);
            if (item != null && normalized.equals(normalize(item.optString("key")))) {
                try { item.put("key", normalized).put("value", clean).put("updatedAt", System.currentTimeMillis()).put("layer", LAYER_SEMANTIC); } catch (JSONException ignored) { }
                replaced = true; break;
            }
        }
        if (!replaced) try { facts.put(new JSONObject().put("key", normalized).put("value", clean).put("updatedAt", System.currentTimeMillis()).put("layer", LAYER_SEMANTIC)); } catch (JSONException ignored) { }
        trim(facts, MAX_FACTS);
        save(FACTS, facts);
    }

    public synchronized boolean forgetFact(String key) {
        if (key == null || key.trim().isEmpty()) return false;
        String normalized = normalize(key); JSONArray facts = read(FACTS); boolean removed = false;
        for (int i = facts.length() - 1; i >= 0; i--) {
            JSONObject item = facts.optJSONObject(i);
            if (item != null && normalized.equals(normalize(item.optString("key")))) { facts.remove(i); removed = true; }
        }
        if (removed) save(FACTS, facts);
        return removed;
    }

    /** Search semantic facts using relevance + recency; bounded for prompt safety. */
    public synchronized JSONArray searchFacts(String query, int limit) {
        JSONArray result = new JSONArray();
        if (query == null || query.trim().isEmpty()) return result;
        String q = query.trim().toLowerCase(Locale.ROOT);
        String[] terms = q.split("\\s+");
        List<ScoredFact> scored = new ArrayList<>();
        JSONArray facts = read(FACTS); long now = System.currentTimeMillis();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject fact = facts.optJSONObject(i); if (fact == null) continue;
            String key = normalize(fact.optString("key")), value = fact.optString("value", "").toLowerCase(Locale.ROOT), text = key + " " + value;
            int score = text.contains(q) ? 8 : 0;
            for (String term : terms) if (term.length() > 1 && text.contains(term)) score += key.contains(term) ? 3 : 1;
            long age = Math.max(0, now - fact.optLong("updatedAt", now)); if (age < 86400000L) score++;
            if (score > 0) scored.add(new ScoredFact(fact, score));
        }
        Collections.sort(scored, (a, b) -> Integer.compare(b.score, a.score));
        int max = Math.min(Math.max(1, limit <= 0 ? 5 : limit), scored.size());
        for (int i = 0; i < max; i++) result.put(scored.get(i).fact);
        return result;
    }

    /** Cross-layer retrieval for diagnostics/tools; recent conversational history remains separate. */
    public synchronized JSONArray searchMemory(String query, int limit) {
        JSONArray out = new JSONArray();
        int max = Math.max(1, Math.min(limit <= 0 ? 8 : limit, 16));
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        appendMatching(out, read(HISTORY), q, max, "content");
        appendMatching(out, read(EPISODES), q, max, "event");
        JSONArray facts = searchFacts(query, max);
        for (int i = 0; i < facts.length() && out.length() < max; i++) out.put(facts.opt(i));
        return out;
    }

    public synchronized String factsSummary() {
        JSONArray facts = read(FACTS); if (facts.length() == 0) return "No saved facts.";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < facts.length(); i++) {
            JSONObject item = facts.optJSONObject(i); if (item == null) continue;
            if (out.length() > 4000) break;
            out.append(item.optString("key")).append(": ").append(item.optString("value")).append('\n');
        }
        return out.toString().trim();
    }

    public synchronized JSONArray recent() { return read(HISTORY); }
    public synchronized JSONArray episodes() { return read(EPISODES); }
    public synchronized JSONArray tasks() { return read(TASKS); }
    public synchronized void clear() { prefs.edit().remove(HISTORY).remove(FACTS).remove(EPISODES).remove(TASKS).apply(); }

    private JSONObject entry(String role, String content) throws JSONException { return new JSONObject().put("role", role).put("content", content).put("timestamp", System.currentTimeMillis()).put("layer", role.equals("episode") ? LAYER_EPISODIC : LAYER_SHORT_TERM); }
    private void appendMatching(JSONArray out, JSONArray source, String q, int max, String field) {
        if (q.isEmpty()) return;
        for (int i = source.length() - 1; i >= 0 && out.length() < max; i--) {
            JSONObject item = source.optJSONObject(i);
            if (item != null && item.optString(field, "").toLowerCase(Locale.ROOT).contains(q)) out.put(item);
        }
    }
    private JSONArray read(String key) { try { return new JSONArray(prefs.getString(key, "[]")); } catch (JSONException e) { return new JSONArray(); } }
    private void save(String key, JSONArray value) { prefs.edit().putString(key, value.toString()).apply(); }
    private static void trim(JSONArray a, int max) { while (a.length() > max) a.remove(0); }
    private static boolean valid(String k, String v) { return k != null && !k.trim().isEmpty() && v != null && !v.trim().isEmpty(); }
    private static String normalize(String k) { return k == null ? "" : k.trim().toLowerCase(Locale.ROOT); }
    private static String bounded(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max); }
    private static final class ScoredFact { final JSONObject fact; final int score; ScoredFact(JSONObject f, int s) { fact = f; score = s; } }
}
