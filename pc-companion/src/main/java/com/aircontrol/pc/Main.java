package com.aircontrol.pc;

import java.nio.file.Path;

/** Entry point for the trusted NOVA PC companion. */
public final class Main {
    public static void main(String[] args) throws Exception {
        String token = System.getenv("NOVA_PC_TOKEN");
        if (token == null || token.length() < 32) {
            System.err.println("NOVA_PC_TOKEN is required and must be at least 32 characters.");
            System.exit(2);
        }
        String bind = env("NOVA_PC_BIND", "0.0.0.0");
        int port = Integer.parseInt(env("NOVA_PC_PORT", "18765"));
        Path workspace = Path.of(env("NOVA_PC_WORKSPACE", System.getProperty("user.dir")));
        PcCompanionServer server = new PcCompanionServer(token, workspace);
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.start(bind, port);
        Thread.currentThread().join();
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}
