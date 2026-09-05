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

    @Test
    public void normalizerPreservesMeaningAcrossWhitespaceAndUnicodeForms() {
        assertEquals("what's 2+2", NovaInputNormalizer.normalize("  what’s\u00A02+2  "));
        assertEquals("Open Chrome and search cats", NovaInputNormalizer.normalize("Open   Chrome\nand\tsearch cats"));
    }

    @Test
    public void normalizerHandlesNullAndEmptyInput() {
        assertEquals("", NovaInputNormalizer.normalize(null));
        assertEquals("", NovaInputNormalizer.normalize("   \u00A0 "));
    }
}
