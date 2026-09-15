package com.aircontrol.pc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
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
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
    private static final int MAX_SEARCH_FILES = 500;
    private static final int MAX_SEARCH_MATCHES = 200;
    private static final long MAX_FILE_BYTES = 1024L * 1024L;
    private static final Set<String> BUILD_EXECUTABLES = Set.of("./gradlew", "gradlew", "gradlew.bat", "mvn", "mvnw", "./mvnw", "npm", "node", "python", "python3");
    private static final Set<String> RUN_EXECUTABLES = Set.of("git", "./gradlew", "gradlew", "gradlew.bat", "mvn", "mvnw", "./mvnw", "npm", "node", "python", "python3");
    private final String secret;
    private final Path workspace;
    private final Path realWorkspace;
    private final Set<String> usedNonces = new HashSet<>();
    private final ExecutorService processExecutor = Executors.newCachedThreadPool();
    private HttpServer server;

    public PcCompanionServer(String secret, Path workspace) throws IOException {
        if (secret == null || secret.length() < 32) throw new IllegalArgumentException("NOVA_PC_TOKEN must be at least 32 characters");
        this.secret = secret;
        this.workspace = workspace.toAbsolutePath().normalize();
        Files.createDirectories(this.workspace);
        this.realWorkspace = this.workspace.toRealPath();
    }

    public int start(String bindHost, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        server.createContext("/v1/health", this::health);
        server.createContext("/v1/execute", this::execute);
        server.createContext("/v1/observe", this::observe);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
        int actualPort = server.getAddress().getPort();
        System.out.println("NOVA PC companion listening on " + bindHost + ":" + actualPort);
        System.out.println("Workspace: " + workspace);
        return actualPort;
    }

    public void stop() {
        if (server != null) server.stop(1);
        processExecutor.shutdownNow();
    }

    private void health(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { send(exchange, 405, error("method_not_allowed").toString()); return; }
        if (!authenticate(exchange, "")) return;
        send(exchange, 200, new JSONObject().put("ok", true).put("service", "nova-pc-companion").put("protocol", 1).put("verified", true).toString());
    }

    private void observe(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) { send(exchange, 405, error("method_not_allowed").toString()); return; }
        if (!authenticate(exchange, "")) return;
        JSONObject out = new JSONObject().put("ok", true).put("os", System.getProperty("os.name", "unknown"))
                .put("arch", System.getProperty("os.arch", "unknown")).put("java", System.getProperty("java.version", "unknown"))
                .put("workspace", workspace.toString()).put("workspaceReal", realWorkspace.toString())
                .put("timestamp", Instant.now().toString()).put("verified", true);
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
            String id = request.optString("id", "").trim();
            String tool = request.optString("tool", "").trim().toLowerCase();
            JSONObject args = request.optJSONObject("args");
            if (id.isEmpty() || tool.isEmpty() || args == null) { send(exchange, 400, error("invalid_request").toString()); return; }
            JSONObject result = executeTool(tool, args);
            result.put("id", id).put("tool", tool).put("verified", result.optBoolean("verified", false));
            send(exchange, result.optBoolean("ok", false) ? 200 : 422, result.toString());
        } catch (PathOutsideWorkspaceException e) {
            send(exchange, 403, error("path_outside_workspace").put("retryable", false).toString());
        } catch (Exception e) {
            send(exchange, 500, error("internal_error").put("retryable", false).put("message", bounded(e.getMessage(), 500)).toString());
        }
    }

    private JSONObject executeTool(String tool, JSONObject args) throws Exception {
        return switch (tool) {
            case "pc_observe" -> new JSONObject().put("ok", true).put("os", System.getProperty("os.name", "unknown"))
                    .put("arch", System.getProperty("os.arch", "unknown")).put("java", System.getProperty("java.version", "unknown"))
                    .put("workspace", workspace.toString()).put("workspaceReal", realWorkspace.toString()).put("verified", true);
            case "pc_project_discover" -> projectDiscover(args);
            case "pc_list_dir" -> listDir(args);
            case "pc_search_text" -> searchText(args);
            case "pc_read_file" -> readFile(args);
            case "pc_write_file" -> writeFile(args);
            case "pc_git_status" -> runCommand(new String[]{"git", "status", "--short", "--branch"}, args);
            case "pc_git_diff" -> runCommand(new String[]{"git", "diff", "--", "."}, args);
            case "pc_build" -> build(args);
            case "pc_run" -> runAllowlisted(args);
            default -> error("tool_not_allowed");
        };
    }

    private JSONObject projectDiscover(JSONObject args) throws Exception {
        Path root = resolveWorkspacePath(args.optString("path", ""));
        if (!Files.isDirectory(root)) return error("not_a_directory");
        JSONArray markers = new JSONArray();
        JSONArray languages = new JSONArray();
        JSONArray sourceDirs = new JSONArray();
        JSONArray testDirs = new JSONArray();
        JSONArray buildCommands = new JSONArray();
        JSONArray testCommands = new JSONArray();
        addIfExists(root, "settings.gradle", markers);
        addIfExists(root, "settings.gradle.kts", markers);
        addIfExists(root, "build.gradle", markers);
        addIfExists(root, "build.gradle.kts", markers);
        addIfExists(root, "pom.xml", markers);
        addIfExists(root, "package.json", markers);
        addIfExists(root, "pyproject.toml", markers);
        addIfExists(root, "requirements.txt", markers);
        addIfExists(root, "Cargo.toml", markers);
        addIfExists(root, "go.mod", markers);
        boolean gradle = Files.exists(root.resolve("gradlew")) || Files.exists(root.resolve("gradlew.bat")) || Files.exists(root.resolve("build.gradle")) || Files.exists(root.resolve("build.gradle.kts"));
        boolean maven = Files.exists(root.resolve("pom.xml")) || Files.exists(root.resolve("mvnw"));
        boolean node = Files.exists(root.resolve("package.json"));
        boolean python = Files.exists(root.resolve("pyproject.toml")) || Files.exists(root.resolve("requirements.txt"));
        if (gradle) { languages.put("Java/Kotlin/Gradle-compatible"); buildCommands.put(Files.exists(root.resolve("gradlew.bat")) ? "gradlew.bat build" : "./gradlew build"); testCommands.put(Files.exists(root.resolve("gradlew.bat")) ? "gradlew.bat test" : "./gradlew test"); }
        if (maven) { languages.put("Java/Maven"); buildCommands.put(Files.exists(root.resolve("mvnw")) ? "./mvnw test" : "mvn test"); testCommands.put(Files.exists(root.resolve("mvnw")) ? "./mvnw test" : "mvn test"); }
        if (node) { languages.put("JavaScript/Node.js"); buildCommands.put("npm test"); testCommands.put("npm test"); }
        if (python) { languages.put("Python"); testCommands.put("python -m pytest"); }
        if (Files.isDirectory(root.resolve("src"))) sourceDirs.put("src");
        for (String name : new String[]{"app/src/main", "src/main", "lib", "source"}) if (Files.isDirectory(root.resolve(name))) sourceDirs.put(name);
        for (String name : new String[]{"app/src/test", "src/test", "tests", "test"}) if (Files.isDirectory(root.resolve(name))) testDirs.put(name);
        String projectType = gradle ? "gradle" : maven ? "maven" : node ? "node" : python ? "python" : Files.exists(root.resolve("Cargo.toml")) ? "rust" : Files.exists(root.resolve("go.mod")) ? "go" : "unknown";
        JSONObject git = new JSONObject().put("present", Files.exists(root.resolve(".git")) || Files.isDirectory(root.resolve(".git")));
        if (git.getBoolean("present")) {
            JSONObject status = runCommand(new String[]{"git", "status", "--short", "--branch"}, new JSONObject().put("path", root.equals(workspace) ? "" : workspace.relativize(root).toString()).put("timeoutMs", 10000));
            git.put("statusOk", status.optBoolean("ok", false)).put("status", status.optString("stdout", "")).put("statusVerified", status.optBoolean("verified", false));
        }
        return new JSONObject().put("ok", true).put("verified", true).put("projectRoot", workspace.relativize(root).toString().replace('\\', '/'))
                .put("projectType", projectType).put("languages", languages).put("markers", markers).put("sourceDirs", sourceDirs).put("testDirs", testDirs)
                .put("buildCommands", buildCommands).put("testCommands", testCommands).put("git", git);
    }

    private void addIfExists(Path root, String name, JSONArray out) { if (Files.exists(root.resolve(name))) out.put(name); }

    private JSONObject listDir(JSONObject args) throws IOException {
        Path dir = resolveWorkspacePath(args.optString("path", ""));
        if (!Files.isDirectory(dir)) return error("not_a_directory");
        JSONArray entries = new JSONArray();
        try (var stream = Files.list(dir)) { stream.limit(500).forEach(p -> entries.put(new JSONObject().put("name", p.getFileName().toString()).put("directory", Files.isDirectory(p)))); }
        return new JSONObject().put("ok", true).put("entries", entries).put("verified", true);
    }

    private JSONObject searchText(JSONObject args) throws Exception {
        String query = args.optString("query", "");
        if (query.isEmpty()) return error("missing_query");
        String extension = args.optString("extension", "").trim().toLowerCase();
        boolean caseSensitive = args.optBoolean("caseSensitive", false);
        String needle = caseSensitive ? query : query.toLowerCase();
        JSONArray matches = new JSONArray();
        int filesScanned = 0;
        try (var stream = Files.walk(workspace)) {
            var iterator = stream.filter(Files::isRegularFile).filter(p -> !isIgnoredPath(p)).iterator();
            while (iterator.hasNext() && filesScanned < MAX_SEARCH_FILES && matches.length() < MAX_SEARCH_MATCHES) {
                Path file = iterator.next();
                if (!extension.isEmpty() && !file.getFileName().toString().toLowerCase().endsWith(extension)) continue;
                filesScanned++;
                byte[] data;
                try { data = Files.readAllBytes(file); } catch (Exception ignored) { continue; }
                if (data.length > 2 * 1024 * 1024) continue;
                String text = new String(data, StandardCharsets.UTF_8);
                String haystack = caseSensitive ? text : text.toLowerCase();
                int offset = 0;
                while (offset < haystack.length() && matches.length() < MAX_SEARCH_MATCHES) {
                    int hit = haystack.indexOf(needle, offset);
                    if (hit < 0) break;
                    int line = 1;
                    for (int i = 0; i < hit && i < text.length(); i++) if (text.charAt(i) == '\n') line++;
                    int lineStart = text.lastIndexOf('\n', Math.max(0, hit - 1)) + 1;
                    int lineEnd = text.indexOf('\n', hit);
                    if (lineEnd < 0) lineEnd = text.length();
                    String snippet = text.substring(lineStart, Math.min(lineEnd, lineStart + 500)).trim();
                    matches.put(new JSONObject().put("path", workspace.relativize(file).toString().replace('\\', '/')).put("line", line).put("snippet", snippet));
                    offset = hit + Math.max(1, query.length());
                }
            }
        }
        return new JSONObject().put("ok", true).put("query", query).put("filesScanned", filesScanned).put("matches", matches).put("truncated", matches.length() >= MAX_SEARCH_MATCHES).put("verified", true);
    }

    private boolean isIgnoredPath(Path file) {
        for (Path part : workspace.relativize(file)) {
            String name = part.toString();
            if (name.equals(".git") || name.equals("build") || name.equals(".gradle") || name.equals("node_modules") || name.equals(".idea")) return true;
        }
        return false;
    }

    private JSONObject readFile(JSONObject args) throws Exception {
        Path file = resolveWorkspacePath(args.optString("path", ""));
        if (!Files.isRegularFile(file)) return error("not_a_file");
        long max = Math.min(Math.max(args.optLong("maxBytes", 256 * 1024), 1), MAX_FILE_BYTES);
        try (InputStream in = Files.newInputStream(file)) {
            byte[] data = in.readNBytes((int) max + 1);
            if (data.length > max) return error("file_too_large");
            return new JSONObject().put("ok", true).put("path", workspace.relativize(file).toString().replace('\\', '/')).put("content", new String(data, StandardCharsets.UTF_8)).put("sha256", sha256(data)).put("verified", true);
        }
    }

    private JSONObject writeFile(JSONObject args) throws Exception {
        Path file = resolveWorkspacePath(args.optString("path", ""));
        byte[] expected = args.optString("content", "").getBytes(StandardCharsets.UTF_8);
        if (expected.length > MAX_FILE_BYTES) return error("file_too_large");
        String expectedSha = args.optString("expectedSha256", "").trim();
        if (!expectedSha.isEmpty()) {
            if (!Files.exists(file)) return error("file_changed_since_inspection").put("retryable", true);
            if (!expectedSha.equals(sha256(Files.readAllBytes(file)))) return error("file_changed_since_inspection").put("retryable", true);
        }
        Path parent = file.getParent();
        if (parent != null) {
            resolveWorkspacePath(workspace.relativize(parent).toString());
            Files.createDirectories(parent);
        }
        Files.write(file, expected, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        byte[] actual = Files.readAllBytes(file);
        return new JSONObject().put("ok", true).put("path", workspace.relativize(file).toString().replace('\\', '/')).put("sha256", sha256(actual)).put("verified", Arrays.equals(actual, expected));
    }

    private JSONObject build(JSONObject args) throws Exception {
        String command = args.optString("command", "").trim();
        if (command.isEmpty()) return error("missing_command");
        String[] parts = tokenizeCommand(command);
        if (parts.length == 0 || !isAllowedExecutable(parts[0], BUILD_EXECUTABLES)) return error("build_command_not_allowed");
        return runCommand(parts, args);
    }

    private JSONObject runAllowlisted(JSONObject args) throws Exception {
        JSONArray command = args.optJSONArray("command");
        if (command == null || command.length() == 0) return error("missing_command");
        String[] parts = new String[command.length()];
        for (int i = 0; i < command.length(); i++) {
            parts[i] = command.optString(i, "").trim();
            if (parts[i].isEmpty()) return error("invalid_command_argument");
        }
        if (!isAllowedExecutable(parts[0], RUN_EXECUTABLES)) return error("command_not_allowed");
        if ("git".equals(parts[0]) && !isAllowedGitCommand(parts)) return error("git_command_not_allowed");
        return runCommand(parts, args);
    }

    private boolean isAllowedExecutable(String executable, Set<String> allowed) {
        if (allowed.contains(executable)) return true;
        String normalized = executable.replace('\\', '/');
        return normalized.endsWith("/gradlew") || normalized.endsWith("/gradlew.bat") || normalized.endsWith("/mvnw");
    }

    private boolean isAllowedGitCommand(String[] command) {
        if (command.length < 2) return false;
        return switch (command[1]) {
            case "status", "diff", "log", "show", "rev-parse" -> true;
            default -> false;
        };
    }

    private JSONObject runCommand(String[] command, JSONObject args) throws Exception {
        Path directory = resolveWorkspacePath(args.optString("path", ""));
        if (!Files.isDirectory(directory)) return error("not_a_directory");
        long timeout = Math.min(Math.max(args.optLong("timeoutMs", 60_000), 1_000), 120_000);
        String operationId = UUID.randomUUID().toString();
        long started = System.nanoTime();
        Process process = new ProcessBuilder(command).directory(directory.toFile()).start();
        Future<StreamCapture> stdout = processExecutor.submit(() -> capture(process.getInputStream(), MAX_OUTPUT));
        Future<StreamCapture> stderr = processExecutor.submit(() -> capture(process.getErrorStream(), MAX_OUTPUT));
        boolean finished = process.waitFor(timeout, TimeUnit.MILLISECONDS);
        if (!finished) {
            process.destroy();
            if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
            StreamCapture out = stdout.get(2, TimeUnit.SECONDS);
            StreamCapture err = stderr.get(2, TimeUnit.SECONDS);
            return new JSONObject().put("ok", false).put("error", "timeout").put("retryable", true).put("verified", false).put("operation_id", operationId).put("exitCode", -1).put("stdout", out.text).put("stderr", err.text).put("output_truncated", out.truncated || err.truncated).put("durationMs", elapsedMillis(started));
        }
        int exit = process.exitValue();
        StreamCapture out = stdout.get(5, TimeUnit.SECONDS);
        StreamCapture err = stderr.get(5, TimeUnit.SECONDS);
        return new JSONObject().put("ok", exit == 0).put("retryable", false).put("exitCode", exit).put("stdout", out.text).put("stderr", err.text).put("output_truncated", out.truncated || err.truncated).put("operation_id", operationId).put("durationMs", elapsedMillis(started)).put("verified", exit == 0);
    }

    private StreamCapture capture(InputStream input, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 4096));
        byte[] buffer = new byte[4096];
        int total = 0;
        boolean truncated = false;
        int read;
        while ((read = input.read(buffer)) != -1) {
            int keep = Math.min(read, maxBytes - total);
            if (keep > 0) { out.write(buffer, 0, keep); total += keep; }
            if (keep < read) truncated = true;
        }
        return new StreamCapture(new String(out.toByteArray(), StandardCharsets.UTF_8), truncated);
    }

    private long elapsedMillis(long startedNanos) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos); }

    private Path resolveWorkspacePath(String relative) throws PathOutsideWorkspaceException, IOException {
        Path candidate = relative == null || relative.isBlank() ? workspace : workspace.resolve(relative).normalize();
        if (!candidate.startsWith(workspace)) throw new PathOutsideWorkspaceException();
        if (Files.exists(candidate)) {
            if (!candidate.toRealPath().startsWith(realWorkspace)) throw new PathOutsideWorkspaceException();
        } else {
            Path parent = candidate.getParent();
            if (parent != null && Files.exists(parent) && !parent.toRealPath().startsWith(realWorkspace)) throw new PathOutsideWorkspaceException();
        }
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
            usedNonces.removeIf(n -> {
                int split = n.lastIndexOf(':');
                if (split <= 0) return true;
                try { return Long.parseLong(n.substring(split + 1)) < ts - MAX_CLOCK_SKEW_SECONDS; }
                catch (NumberFormatException e) { return true; }
            });
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

    private static String[] tokenizeCommand(String command) {
        java.util.ArrayList<String> parts = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        boolean escaping = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaping) { current.append(c); escaping = false; continue; }
            if (c == '\\' && quote != '\'') { escaping = true; continue; }
            if (c == '\'' || c == '"') {
                if (quote == 0) quote = c; else if (quote == c) quote = 0; else current.append(c);
                continue;
            }
            if (Character.isWhitespace(c) && quote == 0) {
                if (current.length() > 0) { parts.add(current.toString()); current.setLength(0); }
            } else current.append(c);
        }
        if (escaping) current.append('\\');
        if (quote != 0) throw new IllegalArgumentException("unterminated_quote");
        if (current.length() > 0) parts.add(current.toString());
        return parts.toArray(new String[0]);
    }

    private static String bounded(String value, int max) { if (value == null) return ""; return value.length() <= max ? value : value.substring(0, max); }
    private static JSONObject error(String code) { return new JSONObject().put("ok", false).put("error", code).put("retryable", false).put("verified", false); }
    private static void send(HttpExchange exchange, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
    private static final class StreamCapture { final String text; final boolean truncated; StreamCapture(String text, boolean truncated) { this.text = text; this.truncated = truncated; } }
    private static final class PathOutsideWorkspaceException extends IOException { }
}
