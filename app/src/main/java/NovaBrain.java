package com.aircontrol;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Central NOVA reasoning layer: context -> model -> tools -> model -> verification -> memory. */
public final class NovaBrain {
    private static final String TAG = "NovaBrain";
    private static final String PREFS = "nova_ai_settings";
    private static final String ENDPOINT = "endpoint";
    private static final String MODEL = "model";
    private static final String PC_PREFS = "nova_pc_settings";
    private static final String PC_ENDPOINT = "endpoint";
    private static final int MAX_QUEUE = 6;
    private static final int MAX_RELEVANT_FACTS = 8;

    public interface Listener {
        void onStatus(String text);
        void onReply(String text);
    }

    private final Context context;
    private final Listener listener;
    private final NovaMemory memory;
    private final NovaSecureStore secureStore;
    private final NovaPcCredentials pcCredentials;
    private final NovaAiClient ai = new NovaAiClient();
    private final NovaActionEngine actions;
    private final NovaAgentPlanner planner;
    private final NovaToolRegistry tools;
    private final NovaWebIntelligence web = new NovaWebIntelligence();
    private final NovaTaskOrchestrator orchestrator = new NovaTaskOrchestrator();
    private final ExecutorService agentExecutor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<String> queue = new ArrayDeque<>();
    private NovaPcToolExecutor pcExecutor;
    private NovaPcHttpClient pcClient;
    private boolean processing;
    private boolean shutdown;
    private long generation;
    private String activeGoal = "";

    public NovaBrain(Context c, NovaActionEngine a, NovaMemory m, Listener l) {
        context = c.getApplicationContext();
        listener = l;
        actions = a;
        memory = m == null ? new NovaMemory(context) : m;
        secureStore = new NovaSecureStore(context);
        pcCredentials = new NovaPcCredentials(context);
        tools = new NovaToolRegistry();
        restorePcCompanion();

        planner = new NovaAgentPlanner(new NovaAgentPlanner.ActionExecutor() {
            @Override
            public boolean execute(String t, String v) {
                return NovaBrain.this.actions != null && NovaBrain.this.actions.execute(t, v);
            }

            @Override
            public String readScreen() {
                GestureAccessibilityService s = GestureAccessibilityService.getInstance();
                return s == null ? "Accessibility service is not connected." : s.getVisibleTextSummary();
            }

            @Override
            public String readUiState() {
                return getUiSnapshot();
            }

            @Override
            public String activePackageName() {
                GestureAccessibilityService s = GestureAccessibilityService.getInstance();
                return s == null ? "" : s.getActivePackageName();
            }

            @Override
            public boolean clickText(String t) {
                GestureAccessibilityService s = GestureAccessibilityService.getInstance();
                return s != null && s.clickText(t);
            }

            @Override
            public boolean clickVisibleIndex(int i) {
                GestureAccessibilityService s = GestureAccessibilityService.getInstance();
                return s != null && s.clickVisibleIndex(i);
            }

            @Override
            public String executeTool(String t, String v) {
                if (isPcTool(t)) return executePcTool(t, v);
                return executeIntelligenceTool(t, v);
            }

            @Override
            public String executeParallel(String v) {
                return executeParallelTools(v);
            }
        }, new NovaAgentPlanner.Listener() {
            @Override
            public void status(String t) {
                NovaBrain.this.status(t);
            }

            @Override
            public void reply(String t) {
                NovaBrain.this.reply(t);
            }
        }, tools);
    }

    /** Configure and persist the authenticated PC companion endpoint and secret. */
    public synchronized boolean configurePcCompanion(String endpoint, String secret) {
        if (shutdown) return false;
        String url = endpoint == null ? "" : endpoint.trim();
        String token = secret == null ? "" : secret.trim();
        if (!(url.startsWith("http://") || url.startsWith("https://")) || token.length() < 32) {
            return false;
        }
        try {
            // Verify the exact endpoint/token before persisting credentials. This prevents
            // NOVA from restoring a dead or mistyped PC companion on the next launch.
            NovaPcHttpClient candidate = new NovaPcHttpClient(url, token);
            if (!candidate.isConnected()) {
                Log.w(TAG, "PC CONFIGURATION REJECTED: " + candidate.getLastError());
                return false;
            }
            pcCredentials.setSecret(token);
            context.getSharedPreferences(PC_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(PC_ENDPOINT, url).apply();
            if (pcClient != null) pcClient.disconnect();
            pcClient = candidate;
            pcExecutor = new NovaPcToolExecutor(candidate);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "PC CONFIGURATION ERROR", e);
            return false;
        }
    }

    /** Remove PC companion credentials and terminate the current client session. */
    public synchronized void clearPcCompanion() {
        if (pcClient != null) pcClient.disconnect();
        pcClient = null;
        pcExecutor = null;
        pcCredentials.clear();
        context.getSharedPreferences(PC_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    public synchronized boolean isPcConnected() {
        return pcExecutor != null && pcExecutor.isConnected();
    }

    public synchronized String observePcCompanion() {
        return pcExecutor == null ? "pc_not_configured" : pcExecutor.observe();
    }

    private void restorePcCompanion() {
        rebuildPcClient();
    }

    private void rebuildPcClient() {
        String endpoint = context.getSharedPreferences(PC_PREFS, Context.MODE_PRIVATE)
                .getString(PC_ENDPOINT, "").trim();
        String secret = pcCredentials.getSecret();
        if (pcClient != null) pcClient.disconnect();
        if (endpoint.isEmpty() || secret.length() < 32) {
            pcClient = null;
            pcExecutor = null;
            return;
        }
        pcClient = new NovaPcHttpClient(endpoint, secret);
        pcExecutor = new NovaPcToolExecutor(pcClient);
    }

    private boolean isPcTool(String type) {
        return type != null && type.trim().toLowerCase().startsWith("pc_");
    }

    private String executePcTool(String type, String value) {
        NovaPcToolExecutor executor;
        synchronized (this) {
            executor = pcExecutor;
        }
        if (executor == null) return "pc_not_configured";
        NovaToolInput input = NovaToolInput.from(type, value);
        NovaToolResult result = executor.execute(input);
        return result.toString();
    }

    // Remaining NovaBrain implementation is unchanged below this point.
