package com.aircontrol.pc;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PcCompanionServerTest {
    private static final String SECRET = "nova-test-secret-0123456789-abcdefghijk";
    private final HttpClient client = HttpClient.newHttpClient();
    private PcCompanionServer server;
    private int port;
    @TempDir Path workspace;

    @BeforeEach void startServer() throws Exception {
        Files.writeString(workspace.resolve("Sample.java"), "class Sample {\n  void hello() {}\n}\n");
        server = new PcCompanionServer(SECRET, workspace);
        port = server.start("127.0.0.1", 0);
    }

    @AfterEach void stopServer() { if (server != null) server.stop(); }

    @Test void healthIsAvailableWithoutAuthentication() throws Exception {
        HttpResponse<String> r = get("/v1/health", false, null);
        assertEquals(200, r.statusCode());
        assertTrue(new JSONObject(r.body()).getBoolean("ok"));
    }

    @Test void observeRequiresValidAuthentication() throws Exception {
        assertEquals(401, get("/v1/observe", false, null).statusCode());
        HttpResponse<String> r = get("/v1/observe", true, null);
        assertEquals(200, r.statusCode());
        assertTrue(new JSONObject(r.body()).getBoolean("ok"));
    }

    @Test void badSignatureIsRejected() throws Exception {
        String nonce = UUID.randomUUID().toString();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(uri("/v1/observe"))
                .header("X-NOVA-Timestamp", timestamp).header("X-NOVA-Nonce", nonce)
                .header("X-NOVA-Signature", "invalid").GET().build();
        HttpResponse<String> r = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, r.statusCode());
        assertEquals("bad_signature", new JSONObject(r.body()).getString("error"));
    }

    @Test void replayedNonceIsRejected() throws Exception {
        String nonce = UUID.randomUUID().toString();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = sign(timestamp, nonce, "");
        HttpRequest request = HttpRequest.newBuilder(uri("/v1/observe"))
                .header("X-NOVA-Timestamp", timestamp).header("X-NOVA-Nonce", nonce)
                .header("X-NOVA-Signature", signature).GET().build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
        HttpResponse<String> replay = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, replay.statusCode());
        assertEquals("replayed_request", new JSONObject(replay.body()).getString("error"));
    }

    @Test void writeAndReadBackAreVerified() throws Exception {
        String body = new JSONObject().put("id", "t1").put("tool", "pc_write_file")
                .put("args", new JSONObject().put("path", "out/result.txt").put("content", "NOVA verified")).toString();
        HttpResponse<String> write = post(body);
        assertEquals(200, write.statusCode());
        assertTrue(new JSONObject(write.body()).getBoolean("verified"));
        assertEquals("NOVA verified", Files.readString(workspace.resolve("out/result.txt")));

        String readBody = new JSONObject().put("id", "t2").put("tool", "pc_read_file")
                .put("args", new JSONObject().put("path", "out/result.txt")).toString();
        HttpResponse<String> read = post(readBody);
        assertEquals(200, read.statusCode());
        assertEquals("NOVA verified", new JSONObject(read.body()).getString("content"));
        assertTrue(new JSONObject(read.body()).getBoolean("verified"));
    }

    @Test void pathTraversalIsRejected() throws Exception {
        String body = new JSONObject().put("id", "t3").put("tool", "pc_read_file")
                .put("args", new JSONObject().put("path", "../outside.txt")).toString();
        HttpResponse<String> r = post(body);
        assertEquals(500, r.statusCode());
        assertEquals("internal_error", new JSONObject(r.body()).getString("error"));
    }

    @Test void searchReturnsSourceLocation() throws Exception {
        String body = new JSONObject().put("id", "t4").put("tool", "pc_search_text")
                .put("args", new JSONObject().put("query", "hello").put("extension", ".java")).toString();
        HttpResponse<String> r = post(body);
        assertEquals(200, r.statusCode());
        JSONObject result = new JSONObject(r.body());
        assertTrue(result.getBoolean("verified"));
        assertTrue(result.getJSONArray("matches").length() >= 1);
        assertEquals("Sample.java", result.getJSONArray("matches").getJSONObject(0).getString("path"));
    }

    private HttpResponse<String> get(String path, boolean auth, String nonceOverride) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(uri(path)).GET();
        if (auth) {
            String nonce = nonceOverride == null ? UUID.randomUUID().toString() : nonceOverride;
            String timestamp = Long.toString(Instant.now().getEpochSecond());
            b.header("X-NOVA-Timestamp", timestamp).header("X-NOVA-Nonce", nonce)
                    .header("X-NOVA-Signature", sign(timestamp, nonce, ""));
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String body) throws Exception {
        String nonce = UUID.randomUUID().toString();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(uri("/v1/execute"))
                .header("Content-Type", "application/json")
                .header("X-NOVA-Timestamp", timestamp).header("X-NOVA-Nonce", nonce)
                .header("X-NOVA-Signature", sign(timestamp, nonce, body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) { return URI.create("http://127.0.0.1:" + port + path); }

    private String sign(String timestamp, String nonce, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "\n" + nonce + "\n" + body).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
