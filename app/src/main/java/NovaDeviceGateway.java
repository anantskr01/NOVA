package com.aircontrol;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Android transport layer for the cross-device NOVA architecture.
 * It follows the useful Jarvis OS daemon protocol while keeping NOVA's
 * existing camera, gesture, accessibility and AI components independent.
 */
public final class NovaDeviceGateway {
    public interface Listener {
        void onStateChanged(String state, boolean connected);
        void onEvent(JSONObject event);
    }

    public interface OperationHandler {
        JSONObject execute(JSONObject operation);
    }

    private static final String TAG = "NovaGateway";
    private static final long RECONNECT_INITIAL_MS = 5000L;
    private static final long RECONNECT_MAX_MS = 60000L;
    private static final long PING_INTERVAL_MS = 25000L;

    private final Context context;
    private final Listener listener;
    private final OperationHandler operationHandler;
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2);

    private volatile WebSocketClient client;
    private volatile boolean enabled;
    private volatile boolean paired;
    private volatile String serverUrl = "";
    private volatile String pairCode = "";
    private volatile String daemonId = "";
    private volatile String reconnectSecret = "";
    private volatile long reconnectDelayMs = RECONNECT_INITIAL_MS;
    private ScheduledFuture<?> pingFuture;
    private ScheduledFuture<?> reconnectFuture;

    public NovaDeviceGateway(Context context, Listener listener, OperationHandler operationHandler) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.operationHandler = operationHandler;
    }

    public synchronized void pair(String serverUrl, String pairCode) {
        this.serverUrl = normalize(serverUrl);
        this.pairCode = pairCode == null ? "" : pairCode.trim();
        this.daemonId = "";
        this.reconnectSecret = "";
        this.paired = false;
        this.enabled = true;
        this.reconnectDelayMs = RECONNECT_INITIAL_MS;
        connect(false);
    }

    public synchronized void reconnect(String serverUrl, String daemonId, String reconnectSecret) {
        this.serverUrl = normalize(serverUrl);
        this.daemonId = daemonId == null ? "" : daemonId.trim();
        this.reconnectSecret = reconnectSecret == null ? "" : reconnectSecret.trim();
        this.pairCode = "";
        this.paired = false;
        this.enabled = true;
        this.reconnectDelayMs = RECONNECT_INITIAL_MS;
        connect(true);
    }

    public synchronized void disconnect() {
        enabled = false;
        paired = false;
        cancelReconnect();
        if (pingFuture != null) pingFuture.cancel(false);
        pingFuture = null;
        WebSocketClient old = client;
        client = null;
        if (old != null) {
            try { old.close(); } catch (Exception ignored) { }
        }
        state("Disconnected", false);
    }

    public boolean isConnected() {
        WebSocketClient current = client;
        return paired && current != null && current.isOpen();
    }

    public String getDaemonId() { return daemonId; }
    public String getReconnectSecret() { return reconnectSecret; }

    public void sendEvent(JSONObject event) {
        WebSocketClient current = client;
        if (event == null || current == null || !current.isOpen()) return;
        try { current.send(event.toString()); }
        catch (Exception e) { Log.w(TAG, "Send failed", e); }
    }

    private synchronized void connect(boolean reconnectMode) {
        if (!enabled || serverUrl.isEmpty()) return;
        WebSocketClient old = client;
        if (old != null) {
            try { old.close(); } catch (Exception ignored) { }
        }

        final boolean useReconnect = reconnectMode && !daemonId.isEmpty() && !reconnectSecret.isEmpty();
        final String wsUrl = buildWsUrl(serverUrl);
        state("Connecting", false);

        client = new WebSocketClient(URI.create(wsUrl)) {
            @Override public void onOpen(ServerHandshake handshake) {
                if (useReconnect) sendReconnect(); else sendPair();
                schedulePing();
            }

            @Override public void onMessage(String message) {
                if (message == null) return;
                try { handleMessage(new JSONObject(message)); }
                catch (Exception e) { Log.w(TAG, "Invalid gateway message", e); }
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                paired = false;
                if (pingFuture != null) pingFuture.cancel(false);
                pingFuture = null;
                state("Disconnected", false);
                scheduleReconnect();
            }

            @Override public void onError(Exception error) {
                Log.w(TAG, "Gateway error", error);
                state("Connection error", false);
            }
        };

        try { client.connect(); }
        catch (Exception e) {
            Log.w(TAG, "Connect failed", e);
            scheduleReconnect();
        }
    }

    private void handleMessage(JSONObject json) {
        String type = json.optString("type", "");
        if ("hello".equals(type)) {
            if (!json.optBoolean("ok", false)) {
                paired = false;
                state("Pairing rejected", false);
                String error = json.optString("error", "").toLowerCase();
                if (error.contains("invalid reconnect secret") || error.contains("unknown daemonid") || error.contains("re-pair")) {
                    daemonId = "";
                    reconnectSecret = "";
                    enabled = false;
                }
                return;
            }
            paired = true;
            reconnectDelayMs = RECONNECT_INITIAL_MS;
            String issuedId = json.optString("daemonId", "");
            String issuedSecret = json.optString("reconnectSecret", "");
            if (!issuedId.isEmpty() && !issuedSecret.isEmpty()) {
                daemonId = issuedId;
                reconnectSecret = issuedSecret;
            }
            state("Connected", true);
            event(json);
            return;
        }

        if ("op".equals(type)) {
            final String id = json.optString("id", "");
            final JSONObject operation = json.optJSONObject("op");
            executor.execute(() -> {
                JSONObject result;
                try {
                    result = operationHandler == null || operation == null
                            ? failure("No NOVA operation handler is connected")
                            : operationHandler.execute(operation);
                } catch (Exception e) {
                    result = failure(e.getMessage() == null ? "Operation failed" : e.getMessage());
                }
                JSONObject response = new JSONObject();
                try {
                    response.put("type", "result");
                    response.put("id", id);
                    response.put("ok", result != null && result.optBoolean("ok", false));
                    if (result != null && result.has("data")) response.put("data", result.get("data"));
                    if (result != null && result.has("error")) response.put("error", result.get("error"));
                } catch (Exception ignored) { }
                sendEvent(response);
            });
            return;
        }

        event(json);
    }

    private JSONObject failure(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("error", message);
        } catch (Exception ignored) { }
        return result;
    }

    private void sendPair() {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "pair");
            message.put("code", pairCode);
            message.put("platform", "android");
            message.put("hostname", Build.MODEL);
        } catch (Exception ignored) { }
        sendEvent(message);
    }

    private void sendReconnect() {
        JSONObject message = new JSONObject();
        try {
            message.put("type", "reconnect");
            message.put("daemonId", daemonId);
            message.put("reconnectSecret", reconnectSecret);
            message.put("platform", "android");
            message.put("hostname", Build.MODEL);
        } catch (Exception ignored) { }
        sendEvent(message);
    }

    private void schedulePing() {
        if (pingFuture != null) pingFuture.cancel(false);
        pingFuture = executor.scheduleAtFixedRate(() -> {
            JSONObject ping = new JSONObject();
            try { ping.put("type", "ping"); } catch (Exception ignored) { }
            sendEvent(ping);
        }, PING_INTERVAL_MS, PING_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void scheduleReconnect() {
        if (!enabled || (reconnectFuture != null && !reconnectFuture.isDone())) return;
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2L, RECONNECT_MAX_MS);
        reconnectFuture = executor.schedule(() -> {
            synchronized (NovaDeviceGateway.this) { reconnectFuture = null; }
            if (enabled) connect(!daemonId.isEmpty() && !reconnectSecret.isEmpty());
        }, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectFuture != null) reconnectFuture.cancel(false);
        reconnectFuture = null;
    }

    private String normalize(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String buildWsUrl(String value) {
        if (value.startsWith("https://")) return value.replaceFirst("^https://", "wss://") + "/api/daemon/ws";
        if (value.startsWith("http://")) return value.replaceFirst("^http://", "ws://") + "/api/daemon/ws";
        if (value.startsWith("wss://") || value.startsWith("ws://")) return value + "/api/daemon/ws";
        return "wss://" + value + "/api/daemon/ws";
    }

    private void state(String value, boolean connected) {
        if (listener != null) listener.onStateChanged(value, connected);
    }

    private void event(JSONObject value) {
        if (listener != null) listener.onEvent(value);
    }

    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }
}
