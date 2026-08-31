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
 * Cross-device transport for NOVA.
 *
 * This is the Android-side equivalent of Jarvis OS's daemon WebSocket layer,
 * adapted to NOVA's existing Java architecture. It deliberately does not own
 * Accessibility, gestures, camera, or AI logic; callers provide an operation
 * handler so those capabilities remain modular.
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
    private static final long INITIAL_RECONNECT_MS = 5000L;
    private static final long MAX_RECONNECT_MS = 60000L;
    private static final long PING_MS = 25000L;

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
    private volatile long reconnectDelayMs = INITIAL_RECONNECT_MS;
    private ScheduledFuture<?> pingFuture;
    private ScheduledFuture<?> reconnectFuture;

    public NovaDeviceGateway(Context context, Listener listener, OperationHandler operationHandler) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.operationHandler = operationHandler;
    }

    /** Start a new pairing session with the central NOVA/Jarvis-compatible server. */
    public synchronized void pair(String serverUrl, String pairCode) {
        this.serverUrl = normalizeServerUrl(serverUrl);
        this.pairCode = pairCode == null ? "" : pairCode.trim();
        this.daemonId = "";
        this.reconnectSecret = "";
        this.paired = false;
        this.enabled = true;
        this.reconnectDelayMs = INITIAL_RECONNECT_MS;
        connect(false);
    }

    /** Reconnect using server-issued credentials supplied by the secure store. */
    public synchronized void reconnect(String serverUrl, String daemonId, String reconnectSecret) {
        this.serverUrl = normalizeServerUrl(serverUrl);
        this.daemonId = daemonId == null ? "" : daemonId.trim();
        this.reconnectSecret = reconnectSecret == null ? "" : reconnectSecret.trim();
        this.pairCode = "";
        this.paired = false;
        this.enabled = true;
        this.reconnectDelayMs = INITIAL_RECONNECT_MS;
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
        notifyState("Disconnected", false);
    }

    public boolean isConnected() {
        return client != null && client.isOpen() && paired;
    }

    /** Send a best-effort event to the central server. */
    public void sendEvent(JSONObject event) {
        if (event == null || client == null || !client.isOpen()) return;
        try { client.send(event.toString()); } catch (Exception e) {
            Log.w(TAG, "Unable to send event", e);
        }
    }

    private synchronized void connect(boolean useReconnectCredentials) {
        if (!enabled || serverUrl.isEmpty()) return;
        if (client != null) {
            try { client.close(); } catch (Exception ignored) { }
        }

        final String wsUrl = buildWsUrl(serverUrl);
        final boolean reconnectMode = useReconnectCredentials
                && !daemonId.isEmpty() && !reconnectSecret.isEmpty();

        notifyState("Connecting", false);

        client = new WebSocketClient(URI.create(wsUrl)) {
            @Override public void onOpen(ServerHandshake handshake) {
                if (reconnectMode) sendReconnect(); else sendPair();
                schedulePing();
            }

            @Override public void onMessage(String message) {
                try {
                    handleMessage(new JSONObject(message));
                } catch (Exception e) {
                    Log.w(TAG, "Invalid gateway message", e);
                }
            }

            @Override public void onClose(int code, String reason, boolean remote) {
                paired = false;
                if (pingFuture != null) pingFuture.cancel(false);
                pingFuture = null;
                notifyState("Disconnected", false);
                scheduleReconnect();
            }

            @Override public void onError(Exception error) {
                Log.w(TAG, "Gateway error", error);
                notifyState("Connection error", false);
            }
        };

        try {
            client.connect();
        } catch (Exception e) {
            Log.w(TAG, "Gateway connect failed", e);
            scheduleReconnect();
        }
    }

    private void handleMessage(JSONObject json) {
        String type = json.optString("type", "");
        if ("hello".equals(type)) {
            if (!json.optBoolean("ok", false)) {
                paired = false;
                notifyState("Pairing rejected", false);
                // Invalid server-issued credentials require explicit re-pairing.
                String error = json.optString("error", "");
                if (error.contains("invalid reconnect secret") || error.contains("unknown daemonId")
                        || error.contains("re-pair")) {
                    daemonId = "";
                    reconnectSecret = "";
                    enabled = false;
                }
                return;
            }
            paired = true;
            reconnectDelayMs = INITIAL_RECONNECT_MS;
            String issuedId = json.optString("daemonId", "");
            String issuedSecret = json.optString("reconnectSecret", "");
            if (!issuedId.isEmpty() && !issuedSecret.isEmpty()) {
                daemonId = issuedId;
                reconnectSecret = issuedSecret;
                // Credentials are intentionally exposed only through getters; the
                // caller should persist them using NovaSecureStore or another
                // Android Keystore-backed mechanism.
            }
            notifyState("Connected", true);
            listenerEvent(json);
            return;
        }

        if ("op".equals(type)) {
            final String opId = json.optString("id", "");
            final JSONObject operation = json.optJSONObject("op");
            executor.execute(() -> {
                JSONObject result = null;
                try {
                    result = operationHandler == null || operation == null
                            ? errorResult("No NOVA operation handler is connected")
                            : operationHandler.execute(operation);
                } catch (Exception e) {
                    result = errorResult(e.getMessage() == null ? "Operation failed" : e.getMessage());
                }
                JSONObject response = new JSONObject();
                try {
                    response.put("type", "result");
                    response.put("id", opId);
                    response.put("ok", result != null && result.optBoolean("ok", false));
                    if (result != null && result.has("data")) response.put("data", result.get("data"));
                    if (result != null && result.has("error")) response.put("error", result.get("error"));
                } catch (Exception ignored) { }
                sendEvent(response);
            });
            return;
        }

        listenerEvent(json);
    }

    private JSONObject errorResult(String message) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", false);
            result.put("error", message == null ? "Operation failed" : message);
        } catch (Exception ignored) { }
        return result;
    }

    private void sendPair() {
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "pair");
            msg.put("code", pairCode);
            msg.put("platform", "android");
            msg.put("hostname", Build.MODEL);
        } catch (Exception ignored) { }
        sendEvent(msg);
    }

    private void sendReconnect() {
        JSONObject msg = new JSONObject();
        try {
            msg.put("type", "reconnect");
            msg.put("daemonId", daemonId);
            msg.put("reconnectSecret", reconnectSecret);
            msg.put("platform", "android");
            msg.put("hostname", Build.MODEL);
        } catch (Exception ignored) { }
        sendEvent(msg);
    }

    private void schedulePing() {
        if (pingFuture != null) pingFuture.cancel(false);
        pingFuture = executor.scheduleAtFixedRate(() -> {
            JSONObject ping = new JSONObject();
            try { ping.put("type", "ping"); } catch (Exception ignored) { }
            sendEvent(ping);
        }, PING_MS, PING_MS, TimeUnit.MILLISECONDS);
    }

    private synchronized void scheduleReconnect() {
        if (!enabled || reconnectFuture != null && !reconnectFuture.isDone()) return;
        long delay = reconnectDelayMs;
        reconnectDelayMs = Math.min(reconnectDelayMs * 2L, MAX_RECONNECT_MS);
        reconnectFuture = executor.schedule(() -> {
            synchronized (NovaDeviceGateway.this) { reconnectFuture = null; }
            if (enabled) connect(!daemonId.isEmpty() && !reconnectSecret.isEmpty());
        }, delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        if (reconnectFuture != null) reconnectFuture.cancel(false);
        reconnectFuture = null;
    }

    private String normalizeServerUrl(String value) {
        if (value == null) return "";
        String base = value.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        return base;
    }

    private String buildWsUrl(String value) {
        if (value.startsWith("https://")) return value.replaceFirst("^https://", "wss://") + "/api/daemon/ws";
        if (value.startsWith("http://")) return value.replaceFirst("^http://", "ws://") + "/api/daemon/ws";
        if (value.startsWith("wss://") || value.startsWith("ws://")) return value + "/api/daemon/ws";
        return "wss://" + value + "/api/daemon/ws";
    }

    private void notifyState(String state, boolean connected) {
        if (listener != null) listener.onStateChanged(state, connected);
    }

    private void listenerEvent(JSONObject event) {
        if (listener != null && event != null) listener.onEvent(event);
    }

    /** Server-issued credentials after a successful first pair. */
    public String getDaemonId() { return daemonId; }
    public String getReconnectSecret() { return reconnectSecret; }

    public void shutdown() {
        disconnect();
        executor.shutdownNow();
    }
}
