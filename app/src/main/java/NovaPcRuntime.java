package com.aircontrol;

import java.io.IOException;

/** Process-wide PC agent binding used by NOVA's action executor. */
public final class NovaPcRuntime {
    private static volatile NovaPcAgent agent;

    private NovaPcRuntime() {}

    public static void configure(NovaPcAgent value) { agent = value; }
    public static NovaPcAgent get() { return agent; }

    public static String execute(String type, String value) throws IOException {
        NovaPcAgent current = agent;
        if (current == null) throw new IOException("PC agent is not configured");
        return switch (type) {
            case "pc_read_file" -> current.readFile(value);
            case "pc_list_files" -> current.listFiles(value);
            default -> throw new IOException("Unsupported PC action: " + type);
        };
    }
}
