package com.aircontrol;

import android.util.Log;
import org.json.JSONArray;
import java.util.ArrayList;
import java.util.List;

/** Provider router. NovaBrain depends on this abstraction rather than a model vendor. */
public final class NovaAiProviderManager {
    private static final String TAG = "NovaProviderManager";
    private final List<NovaAiProvider> providers = new ArrayList<>();

    public NovaAiProviderManager() {
        providers.add(new NovaOllamaProvider());
        providers.add(new NovaOpenAiCompatibleProvider());
    }

    public synchronized void register(NovaAiProvider provider) {
        if (provider == null) return;
        for (NovaAiProvider existing : providers) if (existing.id().equalsIgnoreCase(provider.id())) return;
        providers.add(provider);
    }

    public synchronized String providerId(String endpoint) {
        NovaAiProvider p = select(endpoint);
        return p == null ? "unknown" : p.id();
    }

    public synchronized NovaAiProvider provider(String endpoint) { return select(endpoint); }

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, NovaAiProvider.Callback callback) {
        NovaAiProvider provider;
        synchronized (this) { provider = select(endpoint); }
        if (provider == null) {
            if (callback != null) callback.onError("PROVIDER_UNAVAILABLE: no installed provider supports endpoint");
            return;
        }
        if (provider.requiresApiKey() && (apiKey == null || apiKey.trim().isEmpty())) {
            if (callback != null) callback.onError("AUTH_REQUIRED: provider requires an API key");
            return;
        }
        Log.d(TAG, "Routing request to provider=" + provider.id());
        provider.chat(endpoint, apiKey, model, messages, callback);
    }

    /** Synchronous reachability probe. Run this from a background thread. */
    public NovaProviderHealth.Result healthCheck(String endpoint, String apiKey) {
        NovaAiProvider provider;
        synchronized (this) { provider = select(endpoint); }
        if (provider == null) return new NovaProviderHealth.Result(NovaProviderHealth.State.INVALID_ENDPOINT, 0, 0, "no_supported_provider");
        NovaProviderHealth.Result result = NovaProviderHealth.check(provider, endpoint, apiKey);
        Log.d(TAG, "Health provider=" + provider.id() + " result=" + result);
        return result;
    }

    public synchronized String describe() {
        StringBuilder out = new StringBuilder();
        for (NovaAiProvider provider : providers) {
            if (out.length() > 0) out.append(", ");
            out.append(provider.id()).append("[").append(provider.capabilitySummary()).append("]");
        }
        return out.toString();
    }

    public synchronized void shutdown() { for (NovaAiProvider provider : providers) provider.shutdown(); }

    /** Stable, non-secret failure classification for diagnostics and recovery decisions. */
    public static String classifyFailure(String message) {
        String m = message == null ? "" : message.toLowerCase();
        if (m.contains("timeout") || m.contains("timed out")) return "timeout";
        if (m.contains("401") || m.contains("403") || m.contains("auth") || m.contains("api key")) return "authentication";
        if (m.contains("429")) return "rate_limited";
        if (m.contains("500") || m.contains("502") || m.contains("503") || m.contains("504")) return "provider_server_error";
        if (m.contains("unknown_action") || m.contains("invalid plan") || m.contains("malformed")) return "invalid_ai_output";
        if (m.contains("unreachable") || m.contains("network") || m.contains("connection")) return "network";
        if (m.contains("provider")) return "provider_unavailable";
        return "unknown";
    }

    private NovaAiProvider select(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return null;
        for (NovaAiProvider provider : providers) if (provider.supports(endpoint)) return provider;
        return null;
    }
}
