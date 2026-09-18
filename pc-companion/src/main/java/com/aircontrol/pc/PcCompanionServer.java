package com.aircontrol.pc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class PcCompanionServer implements AutoCloseable {
    private static final long MAX_SKEW_SECONDS = 60;
    private static final int MAX_BODY = 256 * 1024;
    private static final int MAX_PROCESS_OUTPUT = 512 * 1024;
    private static final long PROCESS_TIMEOUT_MILLIS = 110_000L;
    private static final long PROCESS_KILL_GRACE_MILLIS = 5_000L;
    private static final Pattern JSON_STRING = Pattern.compile("\\\"%s\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"");
    private final HttpServer server;
    private final ExecutorService executor;
    private final Path workspace;
    private final Path workspaceReal;
    private final byte[] token;
    private final Map<String, Long> nonces = new ConcurrentHashMap<>();

    private PcCompanionServer(HttpServer server, Path workspace, String token, ExecutorService executor) throws IOException {
        this.server = server;
        this.executor = executor;
        this.workspace = workspace.toAbsolutePath().normalize();
        Files.createDirectories(this.workspace);
        this.workspaceReal = this.workspace.toRealPath();
        this.token = token.getBytes(StandardCharsets.UTF_8);
        registerRoutes();
    }

    public static PcCompanionServer fromEnvironment() throws IOException {
        String workspaceValue = System.getenv().getOrDefault("NOVA_PC_WORKSPACE", System.getProperty("user.dir"));
        String token = System.getenv("NOVA_PC_TOKEN");
        if (token == null || token.length() < 32) throw new IllegalStateException("NOVA_PC_TOKEN must be at least 32 characters");
        Path workspace = Paths.get(workspaceValue).toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        String host = System.getenv().getOrDefault("NOVA_PC_BIND", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("NOVA_PC_PORT", "18765"));
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 32);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        server.setExecutor(executor);
        return new PcCompanionServer(server, workspace, token, executor);
    }

    public void start() { server.start(); }
    public InetSocketAddress address() { return server.getAddress(); }

    private void registerRoutes() {
        server.createContext("/v1/health", e -> {
            if (!"GET".equalsIgnoreCase(e.getRequestMethod())) {
                send(e, 405, "{\"error\":\"method_not_allowed\"}");
                return;
            }
            send(e, 200, "{\"ok\":true,\"service\":\"nova-pc\"}");
        });
        server.createContext("/v1/auth/test", this::authTest);
        server.createContext("/v1/fs/read", this::readFile);
        server.createContext("/v1/fs/write", this::writeFile);
        server.createContext("/v1/fs/list", this::listFiles);
        server.createContext("/v1/process/run", this::runProcess);
    }

    private boolean authenticate(HttpExchange exchange, String body) throws IOException {
        String timestamp = exchange.getRequestHeaders().getFirst("X-NOVA-Timestamp");
        String nonce = exchange.getRequestHeaders().getFirst("X-NOVA-Nonce");
        String signature = exchange.getRequestHeaders().getFirst("X-NOVA-Signature");
        if (timestamp == null || nonce == null || signature == null) {
            send(exchange, 401, "{\"error\":\"missing_auth\"}");
            return false;
        }
        long ts;
        try { ts = Long.parseLong(timestamp); }
        catch (NumberFormatException e) { send(exchange, 401, "{\"error\":\"bad_timestamp\"}"); return false; }
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - ts) > MAX_SKEW_SECONDS) { send(exchange, 401, "{\"error\":\"expired_request\"}"); return false; }
        nonces.entrySet().removeIf(e -> now - e.getValue() > MAX_SKEW_SECONDS * 2);
        String canonical = exchange.getRequestMethod() + "\n" + exchange.getRequestURI().getPath() + "\n" + timestamp + "\n" + nonce + "\n" + body;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token, "HmacSHA256"));
            String expected = HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), signature.getBytes(StandardCharsets.US_ASCII))) {
                send(exchange, 401, "{\"error\":\"invalid_signature\"}");
                return false;
            }
            if (nonces.putIfAbsent(nonce, ts) != null) {
                send(exchange, 409, "{\"error\":\"replay\"}");
                return false;
            }
            return true;
        } catch (Exception e) {
            send(exchange, 500, "{\"error\":\"auth_failure\"}");
            return false;
        }
    }

    private void authTest(HttpExchange e) throws IOException {
        String body = readBody(e);
        if (body == null || !authenticate(e, body)) return;
        send(e, 200, "{\"ok\":true,\"service\":\"nova-pc\",\"authenticated\":true}");
    }

    private void readFile(HttpExchange e) throws IOException {
        try {
            String body = readBody(e);
            if (body == null || !authenticate(e, body)) return;
            String rel = jsonString(body, "path");
            Path file = resolve(rel);
            if (!Files.isRegularFile(file)) { send(e, 404, "{\"error\":\"not_found\"}"); return; }
            if (Files.size(file) > 2 * 1024 * 1024) { send(e, 413, "{\"error\":\"file_too_large\"}"); return; }
            send(e, 200, "{\"path\":" + quote(rel) + ",\"content\":" + quote(Files.readString(file)) + "}");
        } catch (IllegalArgumentException ex) { send(e, 400, "{\"error\":" + quote(ex.getMessage()) + "}"); }
    }

    private void writeFile(HttpExchange e) throws IOException {
        try {
            String body = readBody(e);
            if (body == null || !authenticate(e, body)) return;
            String rel = jsonString(body, "path"), content = jsonString(body, "content");
            if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BODY) { send(e, 413, "{\"error\":\"body_too_large\"}"); return; }
            Path file = resolve(rel);
            Files.createDirectories(file.getParent());
            Files.writeString(file, content);
            send(e, 200, "{\"ok\":true,\"path\":" + quote(rel) + "}");
        } catch (IllegalArgumentException ex) { send(e, 400, "{\"error\":" + quote(ex.getMessage()) + "}"); }
    }

    private void listFiles(HttpExchange e) throws IOException {
        try {
            String body = readBody(e);
            if (body == null || !authenticate(e, body)) return;
            String rel = jsonString(body, "path");
            Path dir = resolve(rel);
            if (!Files.isDirectory(dir)) { send(e, 404, "{\"error\":\"not_directory\"}"); return; }
            StringBuilder out = new StringBuilder("{\"path\":").append(quote(rel)).append(",\"entries\":[");
            boolean first = true;
            try (var stream = Files.list(dir).limit(1000)) {
                for (Path p : (Iterable<Path>) stream::iterator) {
                    if (!first) out.append(',');
                    first = false;
                    out.append("{\"name\":").append(quote(p.getFileName().toString())).append(",\"directory\":").append(Files.isDirectory(p)).append('}');
                }
            }
            send(e, 200, out.append("]}").toString());
        } catch (IllegalArgumentException ex) { send(e, 400, "{\"error\":" + quote(ex.getMessage()) + "}"); }
    }

    private void runProcess(HttpExchange e) throws IOException {
        try {
            String body = readBody(e);
            if (body == null || !authenticate(e, body)) return;
            String command = jsonString(body, "command");
            if (!AllowedCommands.isAllowed(command)) { send(e, 403, "{\"error\":\"command_not_allowlisted\"}"); return; }
            Process p = AllowedCommands.build(command, workspace).redirectErrorStream(true).start();
            ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(MAX_PROCESS_OUTPUT, 64 * 1024));
            Thread reader = new Thread(() -> drainProcessOutput(p.getInputStream(), output), "nova-pc-output");
            reader.setDaemon(true);
            reader.start();

            boolean completed;
            try {
                completed = p.waitFor(PROCESS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                destroyProcessTree(p);
                completed = false;
            }

            boolean timedOut = !completed;
            if (timedOut) {
                destroyProcessTree(p);
                try { p.waitFor(PROCESS_KILL_GRACE_MILLIS, TimeUnit.MILLISECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            }

            try { reader.join(PROCESS_KILL_GRACE_MILLIS); }
            catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            if (reader.isAlive()) reader.interrupt();

            String result = output.toString(StandardCharsets.UTF_8);
            if (timedOut) {
                result = result + (result.isEmpty() ? "" : "\n") + "Process timed out after " + PROCESS_TIMEOUT_MILLIS + " ms.";
                send(e, 200, "{\"exitCode\":124,\"output\":" + quote(result) + ",\"timedOut\":true}");
            } else {
                send(e, 200, "{\"exitCode\":" + p.exitValue() + ",\"output\":" + quote(result) + ",\"timedOut\":false}");
            }
        } catch (IllegalArgumentException ex) { send(e, 400, "{\"error\":" + quote(ex.getMessage()) + "}"); }
    }

    private static void drainProcessOutput(InputStream in, ByteArrayOutputStream output) {
        byte[] buffer = new byte[8192];
        try (InputStream stream = in) {
            int read;
            while ((read = stream.read(buffer)) != -1) {
                synchronized (output) {
                    int remaining = MAX_PROCESS_OUTPUT - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
                }
            }
        } catch (IOException ignored) { }
    }

    private static void destroyProcessTree(Process process) {
        try {
            process.toHandle().descendants().forEach(child -> {
                try { child.destroyForcibly(); } catch (Exception ignored) { }
            });
        } catch (Exception ignored) { }
        try { process.destroyForcibly(); } catch (Exception ignored) { }
    }

    private Path resolve(String relative) {
        if (relative == null || relative.isBlank()) throw new IllegalArgumentException("path required");
        Path candidate = workspace.resolve(relative).normalize();
        if (!candidate.startsWith(workspace)) throw new IllegalArgumentException("path escapes workspace");

        Path existing = candidate;
        while (existing != null && !Files.exists(existing)) existing = existing.getParent();
        if (existing == null) throw new IllegalArgumentException("workspace path has no existing ancestor");
        try {
            if (!existing.toRealPath().startsWith(workspaceReal)) throw new IllegalArgumentException("path escapes workspace");
            if (Files.exists(candidate) && !candidate.toRealPath().startsWith(workspaceReal)) {
                throw new IllegalArgumentException("path escapes workspace");
            }
        } catch (IOException ex) {
            throw new IllegalArgumentException("unable to resolve workspace path");
        }
        return candidate;
    }

    private static String readBody(HttpExchange e) throws IOException {
        if (!"POST".equalsIgnoreCase(e.getRequestMethod())) { send(e, 405, "{\"error\":\"method_not_allowed\"}"); return null; }
        byte[] bytes = e.getRequestBody().readNBytes(MAX_BODY + 1);
        if (bytes.length > MAX_BODY) { send(e, 413, "{\"error\":\"body_too_large\"}"); return null; }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static String jsonString(String json, String key) {
        Matcher m = Pattern.compile(String.format(JSON_STRING.pattern(), Pattern.quote(key))).matcher(json);
        if (!m.find()) throw new IllegalArgumentException("missing field: " + key);
        return unescape(m.group(1));
    }

    private static String unescape(String v) { return v.replace("\\\\", "\\").replace("\\\"", "\"").replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t"); }

    private static String quote(String v) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : v.toCharArray()) switch (c) {
            case '\\' -> b.append("\\\\");
            case '"' -> b.append("\\\"");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default -> b.append(c);
        }
        return b.append('"').toString();
    }

    private static void send(HttpExchange e, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        e.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        e.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = e.getResponseBody()) { out.write(bytes); }
    }

    @Override public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private static final class AllowedCommands {
        static boolean isAllowed(String c) {
            return c.equals("gradlew.bat assembleDebug")
                    || c.equals("gradlew.bat test")
                    || c.equals("git status --short --branch")
                    || c.equals("git diff --check");
        }
        static ProcessBuilder build(String c, Path w) { return new ProcessBuilder("cmd.exe", "/c", c).directory(w.toFile()); }
    }
}
