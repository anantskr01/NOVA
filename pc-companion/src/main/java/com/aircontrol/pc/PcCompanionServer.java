package com.aircontrol.pc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real PC execution companion. It only accepts authenticated, allow-listed operations. */
public final class PcCompanionServer {
    private static final int MAX_BODY = 64 * 1024;
    private static final long MAX_CLOCK_SKEW_SECONDS = 60;
    private static final int MAX_OUTPUT = 12_000;
    private final String secret;
    private final Path workspace;
    private final Set<String> usedNonces = new HashSet<>();
    private HttpServer server;

    public PcCompanionServer(String secret, Path workspace) throws IOException {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("NOVA_PC_TOKEN must be at least 32 characters");
        this.secret = secret;
        this.workspace = workspace.toAbsolutePath().normalize();
        Files.createDirectories(this.workspace);
    }

    public void start(String bindHost, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.createContext("/v1/health", this::health);
        server.createContext("/v1/execute", this::execute);
        server.createContext("/v1/observe", this::observe);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("NOVA PC companion listening on " + bindHost + ":" + port);
        System.out.println("Workspace: " + workspace);
    }

    public void stop() { if (server != null) server.stop(1); }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { send(exchange, 405, error("method_not_allowed").toString()); return; }
        send(exchange, 200, new JSONObject().put("ok", true).put("service", "nova-pc-companion").put("protocol", 1).toString());
    }

    private void observe(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { send(exchange, 405, error("method_not_allowed").toString()); return; }
        if (!authenticate(exchange, "")) return;
        JSONObject out = new JSONObject().put("ok", true).put("os", System.getProperty("os.name", "unknown"))
                .put("arch", System.getProperty("os.arch", "unknown")).put("java", System.getProperty("java.version", "unknown"))
                .put("workspace", workspace.toString()).put("timestamp", Instant.now().toString());
        send(exchange, 200, out.toString());
    }

    private void execute(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) { send(exchange, 405, error("method_not_allowed").toString()); return; }
        String body;
        try (InputStream in = exchange.getRequestBody()) {
            byte[] bytes = in.readNBytes(MAX_BODY + 1);
            if (bytes.length > MAX_BODY) { send(exchange, 413, error("request_too_large").toString()); return; }
            body = new String(bytes, StandardCharsets.UTF_8);
        }
        if (!authenticate(exchange, body)) return;
        try {
            JSONObject request = new JSONObject(body);
            String id = request.optString("id", "");
            String tool = request.optString("tool", "").trim().toLowerCase();
            JSONObject args = request.optJSONObject("args");
            if (id.isEmpty() || tool.isEmpty() || args == null) { send(exchange, 400, error("invalid_request").toString()); return; }
            JSONObject result = executeTool(tool, args);
            result.put("id", id).put("tool", tool).put("verified", result.optBoolean("verified", false));
            send(exchange, result.optBoolean("ok", false) ? 200 : 422, result.toString());
        } catch (Exception e) { send(exchange, 500, error("internal_error").toString()); }
    }

    private JSONObject executeTool(String tool, JSONObject args) throws Exception {
        return switch (tool) {
            case "pc_list_dir" -> listDir(args);
            case "pc_read_file" -> readFile(args);
            case "pc_write_file" -> writeFile(args);
            case "pc_git_status" -> runCommand(new String[]{"git", "status", "--short", "--branch"}, args);
            case "pc_git_diff" -> runCommand(new String[]{"git", "diff", "--", "."}, args);
            case "pc_build" -> build(args);
            case "pc_run" -> runAllowlisted(args);
            default -> error("tool_not_allowed");
        };
    }

    private JSONObject listDir(JSONObject args) throws IOException {
        Path dir = resolveWorkspacePath(args.optString("path", ""));
        if (!Files.isDirectory(dir)) return error("not_a_directory");
        JSONArray entries = new JSONArray();
        try (var stream = Files.list(dir)) { stream.limit(500).forEach(p -> entries.put(new JSONObject().put("name", p.getFileName().toString()).put("directory", Files.isDirectory(p)))); }
        return new JSONObject().put("ok", true).put("entries", entries).put("verified", true);
    }

    private JSONObject readFile(JSONObject args) throws Exception {
        Path file = resolveWorkspacePath(args.optString("path", ""));
        if (!Files.isRegularFile(file)) return error("not_a_file");
        long max = Math.min(args.optLong("maxBytes", 256 * 1024), 1024 * 1024);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] data = in.readNBytes((int) max + 1);
            if (data.length > max) return error("file_too_large");
            return new JSONObject().put("ok", true).put("path", file.toString()).put("content", new String(data, StandardCharsets.UTF_8)).put("sha256", sha256(data)).put("verified", true);
        }
    }

    private JSONObject writeFile(JSONObject args) throws Exception {
        Path file = resolveWorkspacePath(args.optString("path", ""));
        byte[] expected = args.optString("content", "").getBytes(StandardCharsets.UTF_8);
        if (expected.length > 1024 * 1024) return error("file_too_large");
        Files.createDirectories(file.getParent());
        Files.write(file, expected, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        byte[] actual = Files.readAllBytes(file);
        return new JSONObject().put("ok", true).put("path", file.toString()).put("sha256", sha256(actual)).put("verified", Arrays.equals(actual, expected));
    }

    private JSONObject build(JSONObject args) throws Exception {
        String command = args.optString("command", "").trim();
        if (command.isEmpty()) return error("missing_command");
        String executable = command.split("\\s+")[0];
        Set<String> allowed = Set.of("./gradlew", "gradlew.bat", "mvn", "mvnw", "npm", "node", "python", "python3");
        if (!allowed.contains(executable) && !executable.endsWith("/gradlew") && !executable.endsWith("\\gradlew.bat")) return error("build_command_not_allowed");
        return runCommand(command.split("\\s+"), args);
    }

    private JSONObject runAllowlisted(JSONObject args) throws Exception {
        JSONArray command = args.optJSONArray("command");
        if (command == null || command.length() == 0) return error("missing_command");
        String executable = command.optString(0, "");
        if (!Set.of("git", "gradlew.bat", "npm", "node", "python", "python3").contains(executable)) return error("command_not_allowed");
        String[] parts = new String[command.length()];
        for (int i = 0; i < command.length(); i++) parts[i] = command.optString(i, "");
        return runCommand(parts, args);
    }

    private JSONObject runCommand(String[] command, JSONObject args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(resolveWorkspacePath(args.optString("path", "")).toFile());
        pb.redirectErrorStream(true);
        Process process = pb.start();
        long timeout = Math.min(Math.max(args.optLong("timeoutMs", 60_000), 1_000), 120_000);
        boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        if (!finished) { process.destroyForcibly(); return new JSONObject().put("ok", false).put("error", "timeout").put("retryable", true).put("verified", false); }
        byte[] out = process.getInputStream().readNBytes(MAX_OUTPUT);
        int exit = process.exitValue();
        return new JSONObject().put("ok", exit == 0).put("exitCode", exit).put("output", new String(out, StandardCharsets.UTF_8)).put("verified", exit == 0);
    }

    private Path resolveWorkspacePath(String relative) throws IOException {
        Path candidate = relative == null || relative.isBlank() ? workspace : workspace.resolve(relative).normalize();
        if (!candidate.startsWith(workspace)) throw new IOException("path_outside_workspace");
        return candidate;
    }

    private boolean authenticate(HttpExchange exchange, String body) throws IOException {
        String timestamp = exchange.getRequestHeaders().getFirst("X-NOVA-Timestamp");
        String nonce = exchange.getRequestHeaders().getFirst("X-NOVA-Nonce");
        String signature = exchange.getRequestHeaders().getFirst("X-NOVA-Signature");
        if (timestamp == null || nonce == null || signature == null) { send(exchange, 401, error("missing_auth").toString()); return false; }
        long ts;
        try { ts = Long.parseLong(timestamp); } catch (NumberFormatException e) { send(exchange, 401, error("invalid_timestamp").toString()); return false; }
        if (Math.abs(Instant.now().getEpochSecond() - ts) > MAX_CLOCK_SKEW_SECONDS) { send(exchange, 401, error("stale_request").toString()); return false; }
        synchronized (usedNonces) {
            usedNonces.removeIf(n -> { int split = n.lastIndexOf(':'); return split > 0 && Long.parseLong(n.substring(split + 1)) < ts - MAX_CLOCK_SKEW_SECONDS; });
            if (!usedNonces.add(nonce + ":" + ts)) { send(exchange, 401, error("replayed_request").toString()); return false; }
        }
        String expected = hmac(timestamp + "\n" + nonce + "\n" + body);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8))) { send(exchange, 401, error("bad_signature").toString()); return false; }
        return true;
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String sha256(byte[] data) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
    }

    private static JSONObject error(String code) { return new JSONObject().put("ok", false).put("error", code).put("verified", false); }

    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}
