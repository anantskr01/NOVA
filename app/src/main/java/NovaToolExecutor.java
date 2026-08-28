package com.aircontrol;

import android.text.TextUtils;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Executes non-UI NOVA tools that are safe to run without Android UI control.
 * Network work is bounded by time and response-size limits.
 */
public final class NovaToolExecutor {
    private static final String TAG = "NovaToolExecutor";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;
    private static final int MAX_RESPONSE_CHARS = 30000;

    public String execute(String type, String value) {
        if (type == null) return null;
        try {
            switch (type.trim().toLowerCase(Locale.ROOT)) {
                case "web.open":
                case "web.fetch":
                    return fetch(value);
                case "web.search":
                case "search":
                    return search(value);
                default:
                    return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "TOOL ERROR: " + type, e);
            return null;
        }
    }

    private String search(String query) throws Exception {
        if (TextUtils.isEmpty(query)) return null;
        String encoded = URLEncoder.encode(query.trim(), "UTF-8");
        return fetch("https://html.duckduckgo.com/html/?q=" + encoded);
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
            String body = readBounded(stream);
            if (code < 200 || code >= 300) return "HTTP " + code + "\n" + body;
            return stripHtml(body);
        } finally {
            connection.disconnect();
        }
    }

    private String readBounded(InputStream stream) throws Exception {
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[2048];
            int total = 0;
            int read;
            while ((read = reader.read(buffer)) != -1) {
                int remaining = MAX_RESPONSE_CHARS - total;
                if (remaining <= 0) break;
                int take = Math.min(read, remaining);
                out.append(buffer, 0, take);
                total += take;
                if (take < read) break;
            }
        }
        return out.toString();
    }

    private String stripHtml(String input) {
        if (input == null) return null;
        String text = input
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replaceAll("\\s+", " ")
                .trim();
        return text.length() > MAX_RESPONSE_CHARS
                ? text.substring(0, MAX_RESPONSE_CHARS)
                : text;
    }
}
