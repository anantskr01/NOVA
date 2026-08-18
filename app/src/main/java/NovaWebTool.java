package com.aircontrol;

import android.os.Handler;
import android.os.Looper;


import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lightweight web-search tool. It returns short snippets, not whole pages. */
public final class NovaWebTool {
    public interface Callback { void onResult(String text); void onError(String error); }
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    public void search(String query, Callback callback) {
        executor.execute(() -> {
            HttpURLConnection c = null;
            try {
                String q = URLEncoder.encode(query, StandardCharsets.UTF_8.name());
                URL url = new URL("https://html.duckduckgo.com/html/?q=" + q);
                c = (HttpURLConnection) url.openConnection();
                c.setRequestProperty("User-Agent", "NOVA/2.0 Android");
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                int code = c.getResponseCode();
                if (code < 200 || code >= 300) throw new IllegalStateException("Search HTTP " + code);
                StringBuilder html = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
                    String line; while ((line = r.readLine()) != null) html.append(line).append('\n');
                }
                String source = html.toString();
                Pattern p = Pattern.compile("result__a[^>]*>(.*?)</a>.*?result__snippet[^>]*>(.*?)</(?:a|div)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
                Matcher m = p.matcher(source);
                List<String> results = new ArrayList<>();
                while (m.find() && results.size() < 5) {
                    String title = m.group(1).replaceAll("<[^>]+>", "").replaceAll("&quot;", "\"").replaceAll("&amp;", "&").trim();
                    String snippet = m.group(2).replaceAll("<[^>]+>", "").replaceAll("&quot;", "\"").replaceAll("&amp;", "&").trim();
                    if (!title.isEmpty()) results.add("• " + title + (snippet.isEmpty() ? "" : " — " + snippet));
                }
                String out;
                if (results.isEmpty()) {
                    out = "I couldn't extract search snippets. I'll open the search results instead.";
                } else {
                    StringBuilder b = new StringBuilder();
                    for (String item : results) b.append(item).append('\n');
                    out = b.toString().trim();
                }
                main.post(() -> callback.onResult(out));
            } catch (Exception e) {
                main.post(() -> callback.onError(e.getMessage() == null ? "Web search failed" : e.getMessage()));
            } finally { if (c != null) c.disconnect(); }
        });
    }

    public void shutdown() { executor.shutdownNow(); }
}
