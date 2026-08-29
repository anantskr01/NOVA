package com.aircontrol;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Executes bounded web, memory, Android UI, app-discovery and local-workspace tools for NOVA. */
public final class NovaToolExecutor {
    private static final String TAG = "NovaToolExecutor";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_RESPONSE_CHARS = 30000;
    private static final int MAX_FILE_CHARS = 100000;
    private static final int MAX_REDIRECTS = 4;
    private static final int MAX_APPS_CHARS = 12000;

    private final File workspace;
    private final NovaMemory memory;
    private final NovaAppCatalog apps;
    private final NovaActionEngine actions;

    public NovaToolExecutor(Context context) {
        Context app = context.getApplicationContext();
        workspace = new File(app.getFilesDir(), "nova-workspace");
        if (!workspace.exists()) workspace.mkdirs();
        memory = new NovaMemory(app);
        apps = new NovaAppCatalog(app);
        actions = new NovaActionEngine(app, new NovaActionEngine.Callback() {
            @Override public void status(String text) { Log.d(TAG, "ACTION STATUS: " + text); }
            @Override public void reply(String text) { Log.d(TAG, "ACTION REPLY: " + text); }
        });
    }

    public String execute(String type, String value) {
        if (type == null) return null;
        try {
            switch (type.trim().toLowerCase(Locale.ROOT)) {
                case "home": case "back": case "recents": case "notifications":
                case "quick_settings": case "scroll_up": case "scroll_down":
                case "swipe_left": case "swipe_right": case "open_url":
                case "open_package": case "open_app": case "settings":
                    return executeAndroid(type.trim().toLowerCase(Locale.ROOT), value);
                case "click_text": return clickText(value);
                case "click_index": return clickIndex(value);
                case "read_screen": return readScreen();
                case "verify_screen_contains": return verifyScreenContains(value);
                case "web.open":
                case "web.fetch": return fetch(value);
                case "web.search":
                case "search": return search(value);
                case "memory.remember": return remember(value);
                case "memory.recall": return recall(value);
                case "apps.list": return listApps(value);
                case "files.read": return readFile(value);
                case "files.write": return writeFile(value, false);
                case "files.create": return writeFile(value, true);
                case "code.create": return writeFile(value, true);
                case "code.modify": return writeFile(value, false);
                case "communication.send_message":
                case "communication.make_call":
                    return "CONFIRMATION REQUIRED • " + type;
                default: return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "TOOL ERROR: " + type, e);
            return "TOOL ERROR • " + e.getClass().getSimpleName();
        }
    }

    private String executeAndroid(String type, String value) {
        boolean ok = actions.execute(type, value);
        return ok ? "ANDROID • DONE • " + type : "ANDROID • FAILED • " + type;
    }

    private String clickText(String value) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) return "ANDROID • ACCESSIBILITY NOT CONNECTED";
        if (TextUtils.isEmpty(value)) return "ANDROID • CLICK TEXT • MISSING TEXT";
        return service.clickText(value.trim()) ? "ANDROID • CLICKED • " + value.trim() : "ANDROID • CLICK FAILED • " + value.trim();
    }

    private String clickIndex(String value) {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) return "ANDROID • ACCESSIBILITY NOT CONNECTED";
        try {
            int index = Integer.parseInt(value == null ? "" : value.trim());
            return service.clickVisibleIndex(index) ? "ANDROID • CLICKED INDEX • " + index : "ANDROID • CLICK INDEX FAILED • " + index;
        } catch (NumberFormatException e) {
            return "ANDROID • CLICK INDEX • INVALID INDEX";
        }
    }

    private String readScreen() {
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) return "ANDROID • ACCESSIBILITY NOT CONNECTED";
        return service.getUiSnapshot();
    }

    private String verifyScreenContains(String value) {
        String expected = value == null ? "" : value.trim();
        if (expected.isEmpty()) return "VERIFY • FAILED • MISSING EXPECTED TEXT";
        GestureAccessibilityService service = GestureAccessibilityService.getInstance();
        if (service == null) return "VERIFY • FAILED • ACCESSIBILITY NOT CONNECTED";
        String snapshot = service.getUiSnapshot();
        if (snapshot == null || snapshot.trim().isEmpty()) return "VERIFY • FAILED • EMPTY UI";
        String normalizedSnapshot = snapshot.toLowerCase(Locale.ROOT);
        String[] terms = expected.toLowerCase(Locale.ROOT).split("\\s+");
        for (String term : terms) {
            if (!term.isEmpty() && !normalizedSnapshot.contains(term)) {
                return "VERIFY • FAILED • MISSING • " + expected;
            }
        }
        return "VERIFY • PASSED • " + expected;
    }

    private String remember(String value) {
        if (TextUtils.isEmpty(value)) return null;
        try {
            JSONObject request = new JSONObject(value);
            String key = request.optString("key", "note").trim();
            String text = request.optString("value", "").trim();
            if (text.isEmpty()) return null;
            memory.rememberFact(key, text);
            return "MEMORY • SAVED • " + key;
        } catch (Exception e) {
            memory.rememberFact("note", value.trim());
            return "MEMORY • SAVED • note";
        }
    }

    private String recall(String value) {
        String query = value == null ? "" : value.trim();
        String result = memory.recall(query);
        return result == null || result.trim().isEmpty() ? "MEMORY • NO MATCHES" : result;
    }

    private String listApps(String value) {
        int max = 80;
        try {
            if (value != null && !value.trim().isEmpty()) max = Math.max(1, Math.min(200, Integer.parseInt(value.trim())));
        } catch (NumberFormatException ignored) { }
        String result = apps.launchableSummary(max);
        return result.length() > MAX_APPS_CHARS ? result.substring(0, MAX_APPS_CHARS) : result;
    }

    private String search(String query) throws Exception {
        if (TextUtils.isEmpty(query)) return null;
        return fetch("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query.trim(), "UTF-8"));
    }

    private String fetch(String rawUrl) throws Exception {
        if (TextUtils.isEmpty(rawUrl)) return null;
        String current = rawUrl.trim();
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            URI uri = new URI(current);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!("https".equals(scheme) || "http".equals(scheme))) return null;
            if (uri.getUserInfo() != null) return null;
            if (isBlockedHost(uri.getHost())) return "WEB • BLOCKED PRIVATE/LOCAL ADDRESS";
            HttpURLConnection connection = (HttpURLConnection) new URL(current).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("User-Agent", "NOVA/1.0 Android");
            connection.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5");
            try {
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (TextUtils.isEmpty(location)) return "HTTP " + code;
                    current = new URL(new URL(current), location).toString();
                    continue;
                }
                InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
                if (stream == null) return "HTTP " + code;
                String body = readBounded(stream, MAX_RESPONSE_CHARS);
                if (code < 200 || code >= 300) return "HTTP " + code + "\n" + body;
                return stripHtml(body);
            } finally { connection.disconnect(); }
        }
        return "WEB • TOO MANY REDIRECTS";
    }

    private boolean isBlockedHost(String host) throws Exception {
        if (TextUtils.isEmpty(host)) return true;
        String lower = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(lower) || lower.endsWith(".localhost") || lower.endsWith(".local")) return true;
        InetAddress[] addresses = InetAddress.getAllByName(host);
        for (InetAddress address : addresses) {
            if (address.isLoopbackAddress() || address.isAnyLocalAddress() || address.isLinkLocalAddress()
                    || address.isSiteLocalAddress() || isPrivateIpv4(address.getHostAddress())) return true;
        }
        return false;
    }

    private boolean isPrivateIpv4(String ip) {
        if (ip == null || ip.indexOf(':') >= 0) return false;
        String[] parts = ip.split("\\.");
        if (parts.length != 4) return false;
        try {
            int a = Integer.parseInt(parts[0]); int b = Integer.parseInt(parts[1]);
            return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168) || (a == 169 && b == 254);
        } catch (NumberFormatException ignored) { return true; }
    }

    private String readFile(String value) throws Exception {
        String path = parsePath(value); if (path == null) return null;
        File file = resolve(path); if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) { return readBounded(in, MAX_FILE_CHARS); }
    }

    private String writeFile(String value, boolean createOnly) throws Exception {
        if (TextUtils.isEmpty(value)) return null;
        JSONObject request = new JSONObject(value);
        String path = request.optString("path", "").trim(); String content = request.optString("content", "");
        if (path.isEmpty() || content.length() > MAX_FILE_CHARS) return null;
        File file = resolve(path); if (createOnly && file.exists()) return null;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return null;
        try (FileOutputStream out = new FileOutputStream(file, false)) { out.write(content.getBytes(StandardCharsets.UTF_8)); }
        return "FILE • " + (createOnly ? "CREATED" : "WRITTEN") + " • " + path;
    }

    private String parsePath(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            try { return new JSONObject(trimmed).optString("path", "").trim(); } catch (Exception ignored) { return null; }
        }
        return trimmed;
    }

    private File resolve(String relativePath) throws Exception {
        if (TextUtils.isEmpty(relativePath)) throw new SecurityException("empty path");
        File candidate = new File(workspace, relativePath);
        String root = workspace.getCanonicalPath() + File.separator;
        String canonical = candidate.getCanonicalPath();
        if (!canonical.startsWith(root)) throw new SecurityException("path outside workspace");
        return new File(canonical);
    }

    private String readBounded(InputStream stream, int limit) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048]; int total = 0; int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = limit - total; if (remaining <= 0) break;
                int take = Math.min(read, remaining); out.append(buffer, 0, take); total += take;
                if (take < read) break;
            }
        }
        return out.toString();
    }

    private String stripHtml(String input) {
        if (input == null) return null;
        String text = input.replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replaceAll("\\s+", " ").trim();
        return text.length() > MAX_RESPONSE_CHARS ? text.substring(0, MAX_RESPONSE_CHARS) : text;
    }
}
