package com.aircontrol;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded public-web intelligence for NOVA. Search returns structured results;
 * fetch returns cleaned page text. Network work is synchronous by design so
 * the agent loop can feed verified tool output back into the next model turn.
 */
public final class NovaWebIntelligence {
    private static final int MAX_RESULTS = 8;
    private static final int MAX_SEARCH_CHARS = 12000;
    private static final int MAX_PAGE_CHARS = 16000;
    private static final int MAX_REDIRECTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 20000;

    public String search(String query, int requestedMax) throws Exception {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) throw new IllegalArgumentException("query_required");
        int max = Math.max(1, Math.min(MAX_RESULTS, requestedMax <= 0 ? 5 : requestedMax));
        String encoded = URLEncoder.encode(q, StandardCharsets.UTF_8.name());
        HttpURLConnection connection = null;
        try {
            URL url = new URL("https://html.duckduckgo.com/html/?q=" + encoded);
            connection = open(url);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new IllegalStateException("search_http_" + code);
            String html = read(connection.getInputStream(), MAX_SEARCH_CHARS);
            JSONArrayBuilder results = new JSONArrayBuilder();
            Pattern resultPattern = Pattern.compile(
                    "result__a[^>]*href=[\\\"']([^\\\"']+)[\\\"'][^>]*>(.*?)</a>.*?result__snippet[^>]*>(.*?)</(?:a|div)>",
                    Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = resultPattern.matcher(html);
            while (matcher.find() && results.size() < max) {
                String resultUrl = decodeHtml(matcher.group(1)).trim();
                String title = cleanText(matcher.group(2));
                String snippet = cleanText(matcher.group(3));
                if (resultUrl.isEmpty() || title.isEmpty()) continue;
                if (!isPublicHttpUrl(resultUrl)) continue;
                results.add("{\"title\":\"" + escape(title) + "\",\"url\":\"" + escape(resultUrl)
                        + "\",\"snippet\":\"" + escape(snippet) + "\"}");
            }
            return "{\"query\":\"" + escape(q) + "\",\"results\":[" + results.join(",")
                    + "],\"source\":\"DuckDuckGo HTML\"}";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    public String fetch(String rawUrl) throws Exception {
        String current = rawUrl == null ? "" : rawUrl.trim();
        if (!isPublicHttpUrl(current)) throw new IllegalArgumentException("valid_public_http_url_required");
        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = null;
            try {
                connection = open(new URL(current));
                connection.setInstanceFollowRedirects(false);
                int code = connection.getResponseCode();
                if (code >= 300 && code < 400) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.trim().isEmpty()) throw new IllegalStateException("redirect_without_location");
                    current = new URL(new URL(current), location).toString();
                    if (!isPublicHttpUrl(current)) throw new IllegalArgumentException("redirected_to_non_public_url");
                    continue;
                }
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                String body = read(stream, MAX_PAGE_CHARS);
                if (code < 200 || code >= 300) throw new IllegalStateException("web_http_" + code);
                String text = cleanText(body);
                return "{\"url\":\"" + escape(current) + "\",\"status\":" + code
                        + ",\"text\":\"" + escape(text) + "\"}";
            } finally {
                if (connection != null) connection.disconnect();
            }
        }
        throw new IllegalStateException("too_many_redirects");
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
            URI uri = URI.create(raw);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (host == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) return false;
            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress address : addresses) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String read(InputStream stream, int maxChars) throws Exception {
        if (stream == null) return "";
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && out.length() < maxChars) out.append(line).append('\n');
        }
        if (out.length() > maxChars) return out.substring(0, maxChars);
        return out.toString();
    }

    private String cleanText(String html) {
        if (html == null) return "";
        return decodeHtml(html)
                .replaceAll("(?is)<script[^>]*>.*?</script>", " ")
                .replaceAll("(?is)<style[^>]*>.*?</style>", " ")
                .replaceAll("(?is)<noscript[^>]*>.*?</noscript>", " ")
                .replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String decodeHtml(String value) {
        if (value == null) return "";
        return value.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&apos;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&nbsp;", " ");
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " ");
    }

    private static final class JSONArrayBuilder {
        private final List<String> values = new ArrayList<>();
        void add(String value) { values.add(value); }
        int size() { return values.size(); }
        String join(String separator) { return String.join(separator, values); }
    }
}
