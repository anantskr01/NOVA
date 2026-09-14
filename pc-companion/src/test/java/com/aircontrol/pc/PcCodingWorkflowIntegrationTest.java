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
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Deterministic fixture exercising the real authenticated PC coding protocol end-to-end. */
class PcCodingWorkflowIntegrationTest {
    private static final String SECRET = "nova-coding-fixture-secret-0123456789";
    private final HttpClient client = HttpClient.newHttpClient();
    @TempDir Path workspace;
    private PcCompanionServer server;
    private int port;

    @BeforeEach void start() throws Exception {
        Files.writeString(workspace.resolve("app.py"), "def add(a, b):\n    return a - b\n");
        Files.writeString(workspace.resolve("test_app.py"), "import unittest\nfrom app import add\n\nclass AddTest(unittest.TestCase):\n    def test_add(self):\n        self.assertEqual(add(2, 3), 5)\n\nif __name__ == '__main__':\n    unittest.main()\n");
        runGit("init");
        runGit("config", "user.email", "nova-fixture@example.invalid");
        runGit("config", "user.name", "NOVA Fixture");
        runGit("add", ".");
        runGit("commit", "-m", "fixture baseline");
        server = new PcCompanionServer(SECRET, workspace);
        port = server.start("127.0.0.1", 0);
    }

    @AfterEach void stop() { if (server != null) server.stop(); }

    @Test void codingWorkflowInspectsModifiesBuildFailsThenRepairsAndVerifies() throws Exception {
        JSONObject search = execute("pc_search_text", new JSONObject().put("query", "return a - b").put("extension", ".py"));
        assertTrue(search.getBoolean("verified"));
        assertTrue(search.getJSONArray("matches").length() >= 1);

        JSONObject read = execute("pc_read_file", new JSONObject().put("path", "app.py"));
        assertTrue(read.getBoolean("verified"));
        String originalSha = read.getString("sha256");
        assertTrue(read.getString("content").contains("a - b"));

        String wrongFix = "def add(a, b):\n    return a + b + 1\n";
        JSONObject firstWrite = execute("pc_write_file", new JSONObject()
                .put("path", "app.py").put("content", wrongFix).put("expectedSha256", originalSha));
        assertTrue(firstWrite.getBoolean("verified"));

        JSONObject diff = execute("pc_git_diff", new JSONObject());
        assertTrue(diff.getBoolean("verified"));
        assertTrue(diff.getString("stdout").contains("app.py") || diff.getString("stdout").contains("a + b + 1"));

        JSONObject failedTest = execute("pc_run", new JSONObject()
                .put("command", new JSONArray().put("python").put("-m").put("unittest").put("test_app.py"))
                .put("timeoutMs", 10000));
        assertFalse(failedTest.getBoolean("verified"));
        assertNotEquals(0, failedTest.getInt("exitCode"));

        JSONObject staleWrite = execute("pc_write_file", new JSONObject()
                .put("path", "app.py").put("content", "def add(a, b):\n    return a + b\n").put("expectedSha256", originalSha));
        assertEquals("file_changed_since_inspection", staleWrite.getString("error"));
        assertTrue(staleWrite.getBoolean("retryable"));

        JSONObject reread = execute("pc_read_file", new JSONObject().put("path", "app.py"));
        String currentSha = reread.getString("sha256");
        JSONObject correctWrite = execute("pc_write_file", new JSONObject()
                .put("path", "app.py").put("content", "def add(a, b):\n    return a + b\n").put("expectedSha256", currentSha));
        assertTrue(correctWrite.getBoolean("verified"));

        JSONObject build = execute("pc_build", new JSONObject()
                .put("command", "python -m py_compile app.py").put("timeoutMs", 10000));
        assertTrue(build.getBoolean("verified"));
        assertEquals(0, build.getInt("exitCode"));

        JSONObject tests = execute("pc_run", new JSONObject()
                .put("command", new JSONArray().put("python").put("-m").put("unittest").put("test_app.py"))
                .put("timeoutMs", 10000));
        assertTrue(tests.getBoolean("verified"));
        assertEquals(0, tests.getInt("exitCode"));

        JSONObject finalDiff = execute("pc_git_diff", new JSONObject());
        assertTrue(finalDiff.getBoolean("verified"));
        assertTrue(finalDiff.getString("stdout").contains("return a + b"));
    }

    private JSONObject execute(String tool, JSONObject args) throws Exception {
        String body = new JSONObject().put("id", UUID.randomUUID().toString()).put("tool", tool).put("args", args).toString();
        String nonce = UUID.randomUUID().toString();
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/execute"))
                .header("Content-Type", "application/json")
                .header("X-NOVA-Timestamp", timestamp)
                .header("X-NOVA-Nonce", nonce)
                .header("X-NOVA-Signature", sign(timestamp, nonce, body))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        return new JSONObject(response.body());
    }

    private void runGit(String... args) throws Exception {
        String[] command = new String[args.length + 1];
        command[0] = "git";
        System.arraycopy(args, 0, command, 1, args.length);
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
        assertEquals(0, process.waitFor());
    }

    private String sign(String timestamp, String nonce, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] digest = mac.doFinal((timestamp + "\n" + nonce + "\n" + body).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    }
}
