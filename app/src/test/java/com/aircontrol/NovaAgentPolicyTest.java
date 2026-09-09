package com.aircontrol;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class NovaAgentPolicyTest {
    @Test public void allowsKnownSafeActions() {
        assertEquals(NovaAgentPolicy.Decision.ALLOW, NovaAgentPolicy.evaluateAction("home", ""));
        assertEquals(NovaAgentPolicy.Decision.ALLOW, NovaAgentPolicy.evaluateAction("web_search", "android accessibility"));
        assertEquals(NovaAgentPolicy.Decision.ALLOW, NovaAgentPolicy.evaluateAction("open_url", "https://example.com"));
    }

    @Test public void blocksUnknownAndNonWebUrls() {
        assertEquals(NovaAgentPolicy.Decision.BLOCK, NovaAgentPolicy.evaluateAction("run_shell", "whoami"));
        assertEquals(NovaAgentPolicy.Decision.BLOCK, NovaAgentPolicy.evaluateAction("open_url", "file:///secret"));
    }

    @Test public void sensitiveTypingFailsClosedForConfirmation() {
        assertEquals(NovaAgentPolicy.Decision.REQUIRE_CONFIRMATION,
                NovaAgentPolicy.evaluateAction("type_text", "Bearer abcdefghijklmnop"));
        assertTrue(NovaAgentPolicy.requiresConfirmation("type_text", "api_key=abcdefghijk"));
        assertFalse(NovaAgentPolicy.requiresConfirmation("type_text", "hello world"));
    }

    @Test public void credentialDetectorCoversCommonForms() {
        assertTrue(NovaAgentPolicy.looksCredentialLike("password=hunter2"));
        assertTrue(NovaAgentPolicy.looksCredentialLike("authorization: bearer abcdefgh"));
        assertTrue(NovaAgentPolicy.looksCredentialLike("sk-abcdefghijklmnop"));
        assertFalse(NovaAgentPolicy.looksCredentialLike("Use dark mode"));
    }

    @Test public void parallelPolicyOnlyAllowsInformationalPayloads() {
        assertEquals(NovaAgentPolicy.Decision.ALLOW,
                NovaAgentPolicy.evaluateAction("parallel", "[ {\"type\":\"web_search\",\"value\":\"test\"} ]"));
        assertEquals(NovaAgentPolicy.Decision.BLOCK,
                NovaAgentPolicy.evaluateAction("parallel", "[ {\"type\":\"home\",\"value\":\"\"} ]"));
        assertEquals(NovaAgentPolicy.Decision.BLOCK,
                NovaAgentPolicy.evaluateAction("parallel", "[ {\"type\":\"remember\",\"value\":\"{\\\"key\\\":\\\"x\\\",\\\"value\\\":\\\"y\\\"}\"} ]"));
        assertEquals(NovaAgentPolicy.Decision.BLOCK,
                NovaAgentPolicy.evaluateAction("parallel", "not-json"));
    }
}
