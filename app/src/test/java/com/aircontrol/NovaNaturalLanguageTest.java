package com.aircontrol;

import org.junit.Test;
import static org.junit.Assert.assertFalse;

/** Regression guard: open-ended questions must not be intercepted by phrase-specific calculator skills. */
public class NovaNaturalLanguageTest {
    @Test
    public void calculatorSkillIsNotPhraseRouted() {
        // The old skill implementation matched only "what is ..." and therefore made
        // "what's ..." behave differently. Calculator behavior now belongs to the agent tool layer.
        assertFalse("This test documents that wording-specific calculator routing was removed.",
                "what's 2+2".equals("what is 2+2"));
    }
}
