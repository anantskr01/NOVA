package com.aircontrol;

import android.content.Context;
import org.json.JSONArray;

/** Selects NOVA's AI backend without exposing provider details to NovaBrain. */
public final class NovaAiProviderRouter implements NovaAiProvider {
    public static final String PREFS = "nova_ai_settings";
    public static final String PROVIDER = "ai_provider";
    public static final String AUTO = "auto";
    public static final String LOCAL = "local";
    public static final String HTTP = "http";
    public static final String GEMINI = "gemini";
    private static final long FAILURE_COOLDOWN_MS = 15_000L;

    private static volatile String processProvider = AUTO;

    private static final class ProviderHealth {
        int failures;
        long retryAfter;
        synchronized boolean eligible(long now) { return now >= retryAfter; }
        synchronized void success() { failures = 0; retryAfter = 0L; }
        synchronized void failure(long now) {
            failures = Math.min(failures + 1, 5);
            long multiplier = 1L << Math.min(failures - 1, 4);
            retryAfter = now + FAILURE_COOLDOWN_MS * multiplier;
        }
    }

    private final Context context;
    private final NovaLocalAiProvider local = new NovaLocalAiProvider();
    private final NovaHttpAiProvider http = new NovaHttpAiProvider();
    private final NovaGeminiAiProvider gemini = new NovaGeminiAiProvider();
    private final ProviderHealth localHealth = new ProviderHealth();
    private final ProviderHealth httpHealth = new ProviderHealth();
    private final ProviderHealth geminiHealth = new ProviderHealth();

    public NovaAiProviderRouter() { this.context = null; }

    public NovaAiProviderRouter(Context context) {
        this.context = context == null ? null : context.getApplicationContext();
        if (this.context != null) {
            processProvider = normalize(this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PROVIDER, AUTO));
        }
    }

    @Override public String getId() { return "router"; }

    public String getConfiguredProvider() { return configuredProvider(); }

    public boolean setConfiguredProvider(String providerId) {
        String normalized = normalize(providerId);
        if (!isBuiltInProvider(normalized)) return false;
        processProvider = normalized;
        if (context != null) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(PROVIDER, normalized).apply();
        }
        return true;
    }

    public void resetToAuto() { setConfiguredProvider(AUTO); }

    public boolean isBuiltInProvider(String providerId) {
        String id = normalize(providerId);
        return AUTO.equals(id) || LOCAL.equals(id) || HTTP.equals(id) || GEMINI.equals(id);
    }

    @Override
    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        if (callback == null) return;
        String mode = configuredProvider();
        if (LOCAL.equals(mode)) { local.chat(endpoint, apiKey, model, messages, direct(localHealth, local, endpoint, apiKey, model, messages, callback)); return; }
        if (HTTP.equals(mode)) { http.chat(endpoint, apiKey, model, messages, direct(httpHealth, http, endpoint, apiKey, model, messages, callback)); return; }
        if (GEMINI.equals(mode)) { gemini.chat(endpoint, apiKey, model, messages, direct(geminiHealth, gemini, endpoint, apiKey, model, messages, callback)); return; }
        if (AUTO.equals(mode) || mode.isEmpty()) {
            chatAuto(endpoint, apiKey, model, messages, callback, 0, "");
            return;
        }
        callback.onError("Unknown AI provider: " + mode);
    }

    private Callback direct(ProviderHealth health, NovaAiProvider provider, String endpoint, String apiKey,
                            String model, JSONArray messages, Callback callback) {
        return new Callback() {
            @Override public void onResult(String text) {
                health.success();
                callback.onResult(text);
            }
            @Override public void onError(String error) {
                health.failure(System.currentTimeMillis());
                callback.onError(error);
            }
        };
    }

    private void chatAuto(String endpoint, String apiKey, String model, JSONArray messages,
                          Callback callback, int index, String errors) {
        String[] ids = {LOCAL, HTTP, GEMINI};
        if (index >= ids.length) {
            callback.onError("AI providers unavailable. " + errors);
            return;
        }

        int selected = selectAutoProvider(ids, index);
        String id = ids[selected];
        NovaAiProvider provider = provider(id);
        ProviderHealth health = health(id);
        Callback wrapped = new Callback() {
            @Override public void onResult(String text) {
                health.success();
                callback.onResult(text);
            }
            @Override public void onError(String error) {
                health.failure(System.currentTimeMillis());
                String nextErrors = errors + (errors.isEmpty() ? "" : " ") + id + ": " + compact(error) + ";";
                chatAuto(endpoint, apiKey, model, messages, callback, selected + 1, nextErrors);
            }
        };
        provider.chat(endpoint, apiKey, model, messages, wrapped);
    }

    private int selectAutoProvider(String[] ids, int start) {
        long now = System.currentTimeMillis();
        int firstEligible = -1;
        int earliest = start;
        long earliestRetry = Long.MAX_VALUE;
        for (int i = start; i < ids.length; i++) {
            ProviderHealth h = health(ids[i]);
            if (h.eligible(now)) {
                firstEligible = i;
                break;
            }
            long retry = h.retryAfter;
            if (retry < earliestRetry) {
                earliestRetry = retry;
                earliest = i;
            }
        }
        return firstEligible >= 0 ? firstEligible : earliest;
    }

    private NovaAiProvider provider(String id) {
        if (LOCAL.equals(id)) return local;
        if (HTTP.equals(id)) return http;
        return gemini;
    }

    private ProviderHealth health(String id) {
        if (LOCAL.equals(id)) return localHealth;
        if (HTTP.equals(id)) return httpHealth;
        return geminiHealth;
    }

    private String configuredProvider() {
        if (context != null) {
            processProvider = normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(PROVIDER, processProvider));
        }
        return normalize(processProvider);
    }

    private String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(java.util.Locale.US);
    }

    private String compact(String value) {
        if (value == null) return "unknown";
        String cleaned = value.replaceAll("\\s+", " ").trim();
        return cleaned.length() > 180 ? cleaned.substring(0, 180) + "…" : cleaned;
    }

    @Override public void shutdown() {
        local.shutdown();
        http.shutdown();
        gemini.shutdown();
    }
}
