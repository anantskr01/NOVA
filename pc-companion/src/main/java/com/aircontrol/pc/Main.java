package com.aircontrol.pc;

import java.io.IOException;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws IOException {
        PcCompanionServer server = PcCompanionServer.fromEnvironment();
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "nova-pc-shutdown"));
        server.start();
        System.out.println("NOVA PC Companion listening on " + server.address());
    }
}
