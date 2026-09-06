package com.aircontrol;

import org.junit.Test;

import static org.junit.Assert.*;

public class NovaSecurityDiagnosticsTest {
    @Test public void diagnosticsRedactsCommonCredentialForms() {
        String input = "authorization: bearer abc123 api_key=secret123 password=hunter2 token=xyz";
        String output = NovaDiagnostics.compact(input);
        assertFalse(output.contains("abc123"));
        assertFalse(output.contains("secret123"));
        assertFalse(output.contains("hunter2"));
        assertFalse(output.contains("xyz"));
        assertTrue(output.contains("[REDACTED]"));
    }

    @Test public void providerFailuresAreClassifiedWithoutSecrets() {
        assertEquals("timeout", NovaAiProviderManager.classifyFailure("read timeout"));
        assertEquals("authentication", NovaAiProviderManager.classifyFailure("HTTP 401 authentication required"));
        assertEquals("rate_limited", NovaAiProviderManager.classifyFailure("HTTP 429"));
        assertEquals("network", NovaAiProviderManager.classifyFailure("connection refused network unreachable"));
    }
}
