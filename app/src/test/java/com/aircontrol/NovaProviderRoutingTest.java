package com.aircontrol;

import org.junit.Test;
import static org.junit.Assert.*;

public class NovaProviderRoutingTest {
    @Test public void routesOllamaEndpointsToOllama() {
        NovaAiProviderManager manager = new NovaAiProviderManager();
        assertEquals("ollama", manager.providerId("http://192.168.29.210:11434"));
        manager.shutdown();
    }

    @Test public void routesV1EndpointsToOpenAiCompatibleProvider() {
        NovaAiProviderManager manager = new NovaAiProviderManager();
        assertEquals("openai-compatible", manager.providerId("https://api.example.com/v1"));
        manager.shutdown();
    }

    @Test public void exposesPrivacyAndCredentialCapabilities() {
        NovaAiProviderManager manager = new NovaAiProviderManager();
        NovaAiProvider ollama = manager.provider("http://192.168.29.210:11434");
        NovaAiProvider cloud = manager.provider("https://api.example.com/v1");
        assertNotNull(ollama);
        assertNotNull(cloud);
        assertTrue(ollama.localOnly());
        assertFalse(ollama.requiresApiKey());
        assertTrue(cloud.requiresApiKey());
        manager.shutdown();
    }

    @Test public void classifiesProviderFailuresWithoutSecrets() {
        assertEquals("timeout", NovaAiProviderManager.classifyFailure("AI server timed out"));
        assertEquals("authentication", NovaAiProviderManager.classifyFailure("AI HTTP 401"));
        assertEquals("rate_limited", NovaAiProviderManager.classifyFailure("AI HTTP 429"));
        assertEquals("provider_server_error", NovaAiProviderManager.classifyFailure("AI HTTP 503"));
        assertEquals("network", NovaAiProviderManager.classifyFailure("connection refused"));
        assertEquals("invalid_ai_output", NovaAiProviderManager.classifyFailure("unknown_action:foo"));
    }
}
