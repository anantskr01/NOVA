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

    private final Context context;
    private final NovaLocalAiProvider local = new NovaLocalAiProvider();
    private final NovaHttpAiProvider http = new NovaHttpAiProvider();

    public NovaAiProviderRouter(Context context) { this.context = context.getApplicationContext(); }

    @Override public String getId() { return "router"; }

    public String getConfiguredProvider() { return configuredProvider(); }

    public boolean setConfiguredProvider(String providerId) {
        String normalized = normalize(providerId);
        if (normalized.isEmpty()) return false;
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(PROVIDER, normalized).apply();
        return true;
    }

    public void resetToAuto() { setConfiguredProvider(AUTO); }

    public boolean isBuiltInProvider(String providerId) {
        String id = normalize(providerId);
        return AUTO.equals(id) || LOCAL.equals(id) || HTTP.equals(id);
    }

    @Override
    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        String mode = configuredProvider();
        if (LOCAL.equals(mode)) { local.chat(endpoint, apiKey, model, messages, callback); return; }
        if (HTTP.equals(mode)) { http.chat(endpoint, apiKey, model, messages, callback); return; }
        if (AUTO.equals(mode) || mode.isEmpty()) {
            local.chat(endpoint, apiKey, model, messages, new Callback() {
                @Override public void onResult(String text) { callback.onResult(text); }
                @Override public void onError(String message) { http.chat(endpoint, apiKey, model, messages, callback); }
            });
            return;
        }
        callback.onError("Unknown AI provider: " + mode);
    }

    private String configuredProvider() {
        return normalize(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(PROVIDER, AUTO));
    }

    private String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toLowerCase(java.util.Locale.US);
    }

    @Override public void shutdown() { local.shutdown(); http.shutdown(); }
}
