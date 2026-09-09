package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Backward-compatible AI facade. Provider selection is delegated to NovaAiProviderManager. */
public final class NovaAiClient {
    public interface Callback { void onResult(String text); void onError(String message); }

    private final NovaAiProviderManager providers = new NovaAiProviderManager();

    public void chat(String endpoint, String apiKey, String model, JSONArray messages, Callback callback) {
        providers.chat(endpoint, apiKey, model, messages, new NovaAiProvider.Callback() {
            @Override public void onResult(String text) { if (callback != null) callback.onResult(text); }
            @Override public void onError(String message) { if (callback != null) callback.onError(message); }
        });
    }

    public String providerId(String endpoint) { return providers.providerId(endpoint); }
    public String providerSummary() { return providers.describe(); }

    /**
     * Full AI Core verification using the configured model. Unlike a reachability-only
     * health probe, this sends a tiny real inference request through the same provider
     * path used by NOVA Brain.
     * Caller must use a background thread.
     */
    public NovaProviderHealth.Result healthCheck(String endpoint, String apiKey, String model) {
        final long started = System.currentTimeMillis();
        if (endpoint == null || endpoint.trim().isEmpty()) {
            return result(NovaProviderHealth.State.INVALID_ENDPOINT, 0, started, "endpoint_missing");
        }
        final String provider = providerId(endpoint);
        if ("unknown".equals(provider)) {
            return result(NovaProviderHealth.State.INVALID_ENDPOINT, 0, started, "no_supported_provider");
        }

        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> response = new AtomicReference<>("");
        final AtomicReference<String> error = new AtomicReference<>("");
        try {
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", "Reply with exactly: NOVA AI CORE OK"));

            chat(endpoint, apiKey, model, messages, new Callback() {
                @Override public void onResult(String text) {
                    response.set(text == null ? "" : text.trim());
                    latch.countDown();
                }
                @Override public void onError(String message) {
                    error.set(message == null ? "AI request failed" : message);
                    latch.countDown();
                }
            });

            boolean completed = latch.await(95, TimeUnit.SECONDS);
            if (!completed) {
                return result(NovaProviderHealth.State.TIMEOUT, 0, started, "inference_timeout");
            }
            if (!response.get().isEmpty()) {
                NovaDiagnostics.event("provider_inference_test", provider + ":success");
                return result(NovaProviderHealth.State.HEALTHY, 200, started, "inference_ok");
            }

            String message = NovaDiagnostics.compact(error.get());
            String classification = NovaAiProviderManager.classifyFailure(message);
            NovaProviderHealth.State state;
            if ("timeout".equals(classification)) state = NovaProviderHealth.State.TIMEOUT;
            else if ("authentication".equals(classification)) state = NovaProviderHealth.State.UNAUTHORIZED;
            else if ("provider_server_error".equals(classification)) state = NovaProviderHealth.State.SERVER_ERROR;
            else if ("network".equals(classification)) state = NovaProviderHealth.State.UNAVAILABLE;
            else state = NovaProviderHealth.State.UNKNOWN;
            NovaDiagnostics.event("provider_inference_test", provider + ":" + state.name().toLowerCase(Locale.ROOT));
            return result(state, httpCodeFrom(message), started, message.isEmpty() ? "inference_failed" : message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return result(NovaProviderHealth.State.TIMEOUT, 0, started, "inference_interrupted");
        } catch (Exception e) {
            return result(NovaProviderHealth.State.UNKNOWN, 0, started, "inference_probe_error");
        }
    }

    /** Backward-compatible probe overload. */
    public NovaProviderHealth.Result healthCheck(String endpoint, String apiKey) {
        return healthCheck(endpoint, apiKey, "");
    }

    private NovaProviderHealth.Result result(NovaProviderHealth.State state, int code, long started, String detail) {
        return new NovaProviderHealth.Result(state, code,
                Math.max(0, System.currentTimeMillis() - started), detail);
    }

    private int httpCodeFrom(String message) {
        if (message == null) return 0;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("AI HTTP (\\d{3})").matcher(message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    public void shutdown() { providers.shutdown(); }
}
