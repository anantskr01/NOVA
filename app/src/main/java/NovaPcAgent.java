package com.aircontrol;

import java.io.IOException;

/** Transport-neutral PC agent contract consumed by NOVA orchestration. */
public interface NovaPcAgent {
    String readFile(String path) throws IOException;
    void writeFile(String path, String content) throws IOException;
    String listFiles(String path) throws IOException;
    ProcessResult runAllowed(String command) throws IOException;

    record ProcessResult(int exitCode, String output) {}
}
