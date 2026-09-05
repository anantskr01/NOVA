package com.aircontrol;

import org.json.JSONArray;

/** Provider-neutral contract for NOVA's reasoning model backends. */
public interface NovaAiProvider {
    interface Callback {
        void onResult(String text);
        void onError(String message);
    }

    String id();
    boolean supports(String endpoint);
    void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback);
    void shutdown();

    /** Capability metadata used by routing and diagnostics; defaults keep third-party adapters compatible. */
    default boolean localOnly() { return false; }
    default boolean supportsToolCalling() { return false; }
    default boolean supportsStreaming() { return false; }
    default boolean requiresApiKey() { return false; }
    default String healthEndpoint(String endpoint) { return endpoint == null ? "" : endpoint.trim(); }
    default String capabilitySummary() {
        return "localOnly=" + localOnly()
                + ",toolCalling=" + supportsToolCalling()
                + ",streaming=" + supportsStreaming()
                + ",requiresApiKey=" + requiresApiKey();
    }
}
