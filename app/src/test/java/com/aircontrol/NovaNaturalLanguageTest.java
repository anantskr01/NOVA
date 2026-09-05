package com.aircontrol;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** JVM-safe regression tests for wording-independent reasoning primitives. */
public class NovaNaturalLanguageTest {
    @Test
    public void arithmeticToolHandlesEquivalentExpressions() {
        assertEquals("4", NovaCalculator.calculate("2+2"));
        assertEquals("4", NovaCalculator.calculate("2 + 2"));
        assertEquals("4", NovaCalculator.calculate("(2) + (2)"));
    }

    @Test
    public void arithmeticToolRejectsNonArithmeticInput() {
        assertTrue(NovaCalculator.calculate("open chrome").isEmpty());
    }
}
