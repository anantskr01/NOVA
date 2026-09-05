package com.aircontrol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class NovaProviderRoutingTest {
    @Test
    public void routesOllamaEndpointsToOllama() {
        NovaAiProviderManager manager = new NovaAiProviderManager();
        assertEquals("ollama", manager.providerId("http://192.168.29.210:11434"));
        manager.shutdown();
    }

    @Test
    public void routesV1EndpointsToOpenAiCompatibleProvider() {
        NovaAiProviderManager manager = new NovaAiProviderManager();
        assertEquals("openai-compatible", manager.providerId("https://api.example.com/v1"));
        manager.shutdown();
    }
}
