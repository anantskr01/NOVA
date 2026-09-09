package com.aircontrol;

import org.junit.Test;

import static org.junit.Assert.*;

public class NovaMemorySecurityTest {
    @Test public void rejectsCredentialKeys() {
        assertFalse(NovaMemory.isSafeToPersist("api_key", "secret"));
        assertFalse(NovaMemory.isSafeToPersist("password", "anything"));
        assertFalse(NovaMemory.isSafeToPersist("auth_token", "anything"));
        assertTrue(NovaMemory.isSafeToPersist("preferred_browser", "Chrome"));
    }

    @Test public void rejectsCredentialLikeValues() {
        assertFalse(NovaMemory.isSafeToPersist("note", "Bearer abcdefghijklmnop"));
        assertFalse(NovaMemory.isSafeToPersist("note", "api_key=abcdefghijk"));
        assertTrue(NovaMemory.isSafeToPersist("note", "Use dark mode"));
    }
}
