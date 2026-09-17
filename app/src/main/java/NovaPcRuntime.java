package com.aircontrol;

import java.io.IOException;
import org.json.JSONObject;

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
            case "pc_write_file" -> {
                try {
                    JSONObject o = new JSONObject(value);
                    current.writeFile(o.getString("path"), o.getString("content"));
                    yield "{\"ok\":true,\"operation\":\"write\"}";
                } catch (Exception e) { throw new IOException("Invalid pc_write_file payload", e); }
            }
            case "pc_run_allowed" -> {
                NovaPcAgent.ProcessResult r = current.runAllowed(value);
                yield "{\"ok\":true,\"exitCode\":" + r.exitCode() + ",\"output\":\"" + escape(r.output()) + "\"}";
            }
            default -> throw new IOException("Unsupported PC action: " + type);
        };
    }

    private static String escape(String value) { return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", " ").replace("\n", " "); }
}
