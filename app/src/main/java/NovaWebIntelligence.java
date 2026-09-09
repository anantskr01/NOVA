package com.aircontrol;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded public-web intelligence. Search, fetch and multi-source research never expose unbounded page data. */
public final class NovaWebIntelligence {
    private static final int MAX_RESULTS = 8;
    private static final int MAX_SEARCH_CHARS = 12000;
    private static final int MAX_PAGE_CHARS = 16000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_RESEARCH_SOURCES = 4;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    public String search(String query, int requestedMax) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) throw new IllegalArgumentException("query_required");
        int max = Math.max(1, Math.min(MAX_RESULTS, requestedMax <= 0 ? 5 : requestedMax));
        HttpURLConnection c = null;
        try {
            URL url = new URL("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name()));
            c = open(url);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("search_http_" + code);
            String html = read(c.getInputStream(), MAX_SEARCH_CHARS);
            List<String> results = new ArrayList<>();
            Pattern p = Pattern.compile("result__a[^>]*href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>.*?result__snippet[^>]*>(.*?)</(?:a|div)>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher m = p.matcher(html);
            while (m.find() && results.size() < max) {
                String resultUrl = normalizeResultUrl(decodeHtml(m.group(1)));
                String title = cleanText(m.group(2));
                String snippet = cleanText(m.group(3));
                if (resultUrl.isEmpty() || title.isEmpty() || !isPublicHttpUrl(resultUrl)) continue;
                results.add(new JSONObject().put("title", title).put("url", resultUrl).put("snippet", snippet).toString());
            }
            return new JSONObject().put("query", q).put("results", new JSONArray("[" + String.join(",", results) + "]")).put("source", "DuckDuckGo HTML").toString();
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** Search first, then fetch a small number of independent public sources for richer evidence. */
    public String research(String query, int requestedSources) throws Exception {
        int max = Math.max(1, Math.min(MAX_RESEARCH_SOURCES, requestedSources <= 0 ? 3 : requestedSources));
        JSONObject search = new JSONObject(search(query, Math.min(MAX_RESULTS, max + 2)));
        JSONArray results = search.optJSONArray("results");
        JSONArray sources = new JSONArray();
        for (int i = 0; i < results.length() && sources.length() < max; i++) {
            JSONObject hit = results.optJSONObject(i);
            if (hit == null) continue;
            String url = hit.optString("url", "");
            if (url.isEmpty()) continue;
            try {
                JSONObject page = new JSONObject(fetch(url));
                sources.put(new JSONObject()
                        .put("title", hit.optString("title", ""))
                        .put("url", page.optString("url", url))
                        .put("snippet", hit.optString("snippet", ""))
                        .put("excerpt", page.optString("text", "")));
            } catch (Exception ignored) {
                // One unavailable source must not invalidate the remaining independent evidence.
            }
        }
        return new JSONObject().put("query", query == null ? "" : query.trim())
                .put("sources", sources)
                .put("sourceCount", sources.length())
                .put("searchSource", search.optString("source", ""))
                .toString();
    }

    public String fetch(String rawUrl) throws Exception {
        String current = rawUrl == null ? "" : rawUrl.trim();
        if (!isPublicHttpUrl(current)) throw new IllegalArgumentException("valid_public_http_url_required");
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection c = null;
            try {
                c = open(new URL(current));
                c.setInstanceFollowRedirects(false);
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = c.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IllegalStateException("redirect_without_location");
                    current = new URL(new URL(current), location).toString();
                    if (!isPublicHttpUrl(current)) throw new IllegalArgumentException("redirected_to_non_public_url");
                    continue;
                }
                InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
                String body = read(stream, MAX_PAGE_CHARS);
                if (code < 200 || code >= 300) throw new IllegalStateException("web_http_" + code);
                return new JSONObject().put("url", current).put("status", code).put("text", cleanText(body)).toString();
            } finally {
                if (c != null) c.disconnect();
            }
        }
        throw new IllegalStateException("too_many_redirects");
    }

    private String normalizeResultUrl(String raw) {
        try {
            URI u = URI.create(raw);
            String query = u.getRawQuery();
            if (query != null) {
                for (String part : query.split("&")) {
                    int eq = part.indexOf('=');
                    if (eq > 0 && "uddg".equalsIgnoreCase(URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8.name()))) {
                        return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8.name());
                    }
                }
            }
            return raw;
        } catch (Exception e) {
            return raw;
        }
    }

    private HttpURLConnection open(URL url) throws Exception {
        if (!isPublicHttpUrl(url.toString())) throw new IllegalArgumentException("public_http_url_required");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setRequestProperty("User-Agent", "NOVA/3.0 Android");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
        return c;
    }

    private boolean isPublicHttpUrl(String raw) {
        try {
            URI u = URI.create(raw);
            String scheme = u.getScheme(), host = u.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            for (InetAddress a : InetAddress.getAllByName(host)) {
                if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String read(InputStream stream, int max) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null && out.length() < max) out.append(line).append('\n');
        }
        return out.length() > max ? out.substring(0, max) : out.toString();
    }

    private String cleanText(String html) {
        if (html == null) return "";
        return decodeHtml(html)
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ").trim();
    }

    private String decodeHtml(String v) {
        if (v == null) return "";
        return v.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
    }

    public void shutdown() { }
}
