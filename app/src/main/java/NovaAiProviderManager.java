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
        for (NovaAiProvider existing : providers) {
            if (existing.id().equalsIgnoreCase(provider.id())) return;
        }
        providers.add(provider);
    }

    public synchronized String providerId(String endpoint) {
        NovaAiProvider p = select(endpoint);
        return p == null ? "unknown" : p.id();
    }

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, NovaAiProvider.Callback callback) {
        NovaAiProvider provider;
        synchronized (this) { provider = select(endpoint); }
        if (provider == null) {
            callback.onError("No installed AI provider supports endpoint: " + compact(endpoint));
            return;
        }
        Log.d(TAG, "Routing request to provider=" + provider.id());
        provider.chat(endpoint, apiKey, model, messages, callback);
    }

    private NovaAiProvider select(String endpoint) {
        if (endpoint == null || endpoint.trim().isEmpty()) return null;
        for (NovaAiProvider provider : providers) {
            if (provider.supports(endpoint)) return provider;
        }
        return null;
    }

    public synchronized String describe() {
        StringBuilder out = new StringBuilder();
        for (NovaAiProvider provider : providers) {
            if (out.length() > 0) out.append(", ");
            out.append(provider.id());
        }
        return out.toString();
    }

    public synchronized void shutdown() {
        for (NovaAiProvider provider : providers) provider.shutdown();
    }

    private String compact(String value) {
        if (value == null) return "";
        String s = value.trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}
