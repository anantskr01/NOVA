package com.aircontrol.pc;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

final class PcCompanionServerTest {
    @Test
    void mainClassIsPresent() throws Exception {
        Class<?> main = Class.forName("com.aircontrol.pc.Main");
        Method method = main.getDeclaredMethod("main", String[].class);
        assertTrue(method != null);
    }
}
