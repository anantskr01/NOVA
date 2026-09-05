package com.aircontrol;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Bounded provider reachability probe. Call from a background thread. */
public final class NovaProviderHealth {
    public enum State { HEALTHY, UNAVAILABLE, UNAUTHORIZED, SERVER_ERROR, TIMEOUT, INVALID_ENDPOINT, UNKNOWN }

    public static final class Result {
        public final State state;
        public final int httpCode;
        public final long latencyMillis;
        public final String detail;

        Result(State state, int code, long latency, String detail) {
            this.state = state; this.httpCode = code; this.latencyMillis = latency; this.detail = detail == null ? "" : detail;
        }
        public boolean isHealthy() { return state == State.HEALTHY; }
        @Override public String toString() { return state + "(" + httpCode + "," + latencyMillis + "ms):" + detail; }
    }

    private NovaProviderHealth() { }

    public static Result check(NovaAiProvider provider, String endpoint, String apiKey) {
        long started = System.currentTimeMillis();
        if (provider == null || endpoint == null || endpoint.trim().isEmpty()) return result(State.INVALID_ENDPOINT, 0, started, "provider_or_endpoint_missing");
        if (!provider.supports(endpoint)) return result(State.INVALID_ENDPOINT, 0, started, "provider_does_not_support_endpoint");
        String target = provider.healthEndpoint(endpoint);
        if (target == null || !(target.startsWith("http://") || target.startsWith("https://"))) return result(State.INVALID_ENDPOINT, 0, started, "invalid_health_endpoint");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(target).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (apiKey != null && !apiKey.trim().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + apiKey.trim());
            int code = connection.getResponseCode();
            InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
            if (stream != null) try { stream.close(); } catch (Exception ignored) { }
            State state = code >= 200 && code < 300 ? State.HEALTHY : code == 401 || code == 403 ? State.UNAUTHORIZED : code >= 500 ? State.SERVER_ERROR : State.UNAVAILABLE;
            return result(state, code, started, "http_" + code);
        } catch (java.net.SocketTimeoutException e) {
            return result(State.TIMEOUT, 0, started, "timeout");
        } catch (IllegalArgumentException e) {
            return result(State.INVALID_ENDPOINT, 0, started, "invalid_endpoint");
        } catch (Exception e) {
            return result(State.UNAVAILABLE, 0, started, "unreachable");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static Result result(State state, int code, long started, String detail) {
        return new Result(state, code, Math.max(0, System.currentTimeMillis() - started), detail);
    }
}
