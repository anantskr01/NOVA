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
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Executes bounded web, memory and local-workspace tools for NOVA. */
public final class NovaToolExecutor {
    private static final String TAG = "NovaToolExecutor";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_RESPONSE_CHARS = 30000;
    private static final int MAX_FILE_CHARS = 100000;

    private final File workspace;
    private final NovaMemory memory;

    public NovaToolExecutor(Context context) {
        Context app = context.getApplicationContext();
        workspace = new File(app.getFilesDir(), "nova-workspace");
        if (!workspace.exists()) workspace.mkdirs();
        memory = new NovaMemory(app);
    }

    public String execute(String type, String value) {
        if (type == null) return null;
        try {
            switch (type.trim().toLowerCase(Locale.ROOT)) {
                case "web.open":
                case "web.fetch": return fetch(value);
                case "web.search":
                case "search": return search(value);
                case "memory.remember": return remember(value);
                case "memory.recall": return recall(value);
                case "files.read": return readFile(value);
                case "files.write": return writeFile(value, false);
                case "files.create": return writeFile(value, true);
                case "code.create": return writeFile(value, true);
                case "code.modify": return writeFile(value, false);
                default: return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "TOOL ERROR: " + type, e);
            return null;
        }
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

    private String search(String query) throws Exception {
        if (TextUtils.isEmpty(query)) return null;
        return fetch("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query.trim(), "UTF-8"));
    }

    private String fetch(String rawUrl) throws Exception {
        if (TextUtils.isEmpty(rawUrl)) return null;
        String value = rawUrl.trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("https://") && !lower.startsWith("http://")) return null;
        HttpURLConnection connection = (HttpURLConnection) new URL(value).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(READ_TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "NOVA/1.0 Android");
        connection.setRequestProperty("Accept", "text/html,text/plain,application/json;q=0.9,*/*;q=0.5");
        try {
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream == null) return "HTTP " + code;
            String body = readBounded(stream, MAX_RESPONSE_CHARS);
            if (code < 200 || code >= 300) return "HTTP " + code + "\n" + body;
            return stripHtml(body);
        } finally { connection.disconnect(); }
    }

    private String readFile(String value) throws Exception {
        String path = parsePath(value);
        if (path == null) return null;
        File file = resolve(path);
        if (!file.isFile()) return null;
        try (FileInputStream in = new FileInputStream(file)) { return readBounded(in, MAX_FILE_CHARS); }
    }

    private String writeFile(String value, boolean createOnly) throws Exception {
        if (TextUtils.isEmpty(value)) return null;
        JSONObject request = new JSONObject(value);
        String path = request.optString("path", "").trim();
        String content = request.optString("content", "");
        if (path.isEmpty() || content.length() > MAX_FILE_CHARS) return null;
        File file = resolve(path);
        if (createOnly && file.exists()) return null;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) return null;
        try (FileOutputStream out = new FileOutputStream(file, false)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return "FILE • " + (createOnly ? "CREATED" : "WRITTEN") + " • " + path;
    }

    private String parsePath(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String trimmed = value.trim();
        if (trimmed.startsWith("{")) {
            try { return new JSONObject(trimmed).optString("path", "").trim(); }
            catch (Exception ignored) { return null; }
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
