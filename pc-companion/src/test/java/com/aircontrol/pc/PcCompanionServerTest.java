package com.aircontrol.pc;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PcCompanionServerTest {
    private static final String TOKEN = "0123456789abcdefghijklmnopqrstuvwxyzABCD";
    private final HttpClient client = HttpClient.newHttpClient();
    private Path workspace;
    private ExecutorService executor;
    private PcCompanionServer companion;

    @BeforeEach
    void setUp() throws Exception {
        workspace = Files.createTempDirectory("nova-pc-test");
        executor = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(executor);
        companion = new PcCompanionServer(server, workspace, TOKEN, executor);
        companion.start();
    }

    @AfterEach
    void tearDown() {
        if (companion != null) companion.close();
    }

    @Test
    void healthIsPublicAndReportsService() throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(base("/v1/health")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"ok\":true"));
        assertTrue(response.body().contains("\"service\":\"nova-pc\""));
    }

    @Test
    void authenticatedPairingAcceptsCorrectToken() throws Exception {
        HttpResponse<String> response = sendSigned("/v1/auth/test", "{}", TOKEN, UUID.randomUUID().toString());

        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"authenticated\":true"));
    }

    @Test
    void wrongTokenIsRejected() throws Exception {
        HttpResponse<String> response = sendSigned(
                "/v1/auth/test",
                "{}",
                "wrong-token-should-fail-abcdefghijklmnopqrstuvwxyz",
                UUID.randomUUID().toString());

        assertEquals(401, response.statusCode());
        assertTrue(response.body().contains("invalid_signature"));
    }

    @Test
    void replayedNonceIsRejected() throws Exception {
        String nonce = UUID.randomUUID().toString();

        HttpResponse<String> first = sendSigned("/v1/auth/test", "{}", TOKEN, nonce);
        HttpResponse<String> second = sendSigned("/v1/auth/test", "{}", TOKEN, nonce);

        assertEquals(200, first.statusCode());
        assertEquals(409, second.statusCode());
        assertTrue(second.body().contains("replay"));
    }

    @Test
    void workspaceEscapeIsRejected() throws Exception {
        String body = "{\"path\":\"../outside.txt\",\"content\":\"blocked\"}";

        HttpResponse<String> response = sendSigned(
                "/v1/fs/write",
                body,
                TOKEN,
                UUID.randomUUID().toString());

        assertEquals(400, response.statusCode());
        assertTrue(response.body().contains("path escapes workspace"));
    }

    @Test
    void authenticatedNestedFileWriteAndReadWorks() throws Exception {
        String writeBody = "{\"path\":\"nested/hello.txt\",\"content\":\"hello-nova\"}";
        HttpResponse<String> write = sendSigned(
                "/v1/fs/write",
                writeBody,
                TOKEN,
                UUID.randomUUID().toString());

        assertEquals(200, write.statusCode());
        assertTrue(write.body().contains("\"ok\":true"));

        String readBody = "{\"path\":\"nested/hello.txt\"}";
        HttpResponse<String> read = sendSigned(
                "/v1/fs/read",
                readBody,
                TOKEN,
                UUID.randomUUID().toString());

        assertEquals(200, read.statusCode());
        assertTrue(read.body().contains("hello-nova"));
    }

    @Test
    void arbitraryProcessCommandIsRejected() throws Exception {
        String body = "{\"command\":\"whoami\"}";

        HttpResponse<String> response = sendSigned(
                "/v1/process/run",
                body,
                TOKEN,
                UUID.randomUUID().toString());

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("command_not_allowlisted"));
    }

    private HttpResponse<String> sendSigned(String route, String body, String signingToken, String nonce) throws Exception {
        String timestamp = Long.toString(System.currentTimeMillis() / 1000L);
        String canonical = "POST\n" + route + "\n" + timestamp + "\n" + nonce + "\n" + body;
        String signature = hmac(signingToken, canonical);

        return client.send(
                HttpRequest.newBuilder(base(route))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("X-NOVA-Timestamp", timestamp)
                        .header("X-NOVA-Nonce", nonce)
                        .header("X-NOVA-Signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI base(String route) {
        return URI.create("http://127.0.0.1:" + companion.address().getPort() + route);
    }

    private static String hmac(String token, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
