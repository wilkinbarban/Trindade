package com.trindade.app.reports

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for temperature entry validation and decimal/comma parsing in [ReportForm].
 *
 * Verifies that:
 * 1. Cold-storage negative readings (e.g. -18°C) are enterable, including the in-progress lone minus sign.
 * 2. Portuguese comma decimals (e.g. -18,5 and 4,5) and standard period decimals (-18.5, 4.5) are valid.
 * 3. Malformed inputs (multiple minus signs, multiple commas/dots, letters, special characters) are rejected.
 * 4. [toggleMinusSign] prefixes and removes a leading minus sign while preserving dot/comma formats and handling empty/in-progress states.
 * 5. [String.toReading] correctly parses valid signed comma/period decimals into [BigDecimal] and yields null
 *    for incomplete or invalid inputs.
 */
class ReportFormValidationTest {

    @Test
    fun `valid temperature inputs accept positive and negative signed decimals`() {
        // Empty string allows clearing the field
        assertTrue("Empty string must be valid", isValidTemperatureInput(""))

        // In-progress entry: operator types minus first
        assertTrue("Lone minus must be valid for in-progress entry", isValidTemperatureInput("-"))

        // Integers
        assertTrue("Positive integer must be valid", isValidTemperatureInput("4"))
        assertTrue("Negative integer must be valid", isValidTemperatureInput("-18"))
        assertTrue("Zero must be valid", isValidTemperatureInput("0"))
        assertTrue("Negative zero must be valid", isValidTemperatureInput("-0"))

        // Comma decimals (Portuguese convention)
        assertTrue("Positive comma decimal must be valid", isValidTemperatureInput("4,5"))
        assertTrue("Negative comma decimal must be valid", isValidTemperatureInput("-18,5"))
        assertTrue("In-progress comma decimal must be valid", isValidTemperatureInput("4,"))
        assertTrue("In-progress negative comma decimal must be valid", isValidTemperatureInput("-18,"))

        // Period decimals
        assertTrue("Positive dot decimal must be valid", isValidTemperatureInput("4.5"))
        assertTrue("Negative dot decimal must be valid", isValidTemperatureInput("-18.5"))
        assertTrue("In-progress dot decimal must be valid", isValidTemperatureInput("4."))
        assertTrue("In-progress negative dot decimal must be valid", isValidTemperatureInput("-18."))

        // Unicode minus
        assertTrue("Unicode lone minus must be valid", isValidTemperatureInput("−"))
        assertTrue("Unicode negative decimal must be valid", isValidTemperatureInput("−18.5"))
        assertTrue("Unicode negative comma decimal must be valid", isValidTemperatureInput("−18,5"))

        // Leading separator
        assertTrue("Leading comma must be valid", isValidTemperatureInput(",5"))
        assertTrue("Leading dot must be valid", isValidTemperatureInput(".5"))
        assertTrue("Negative leading comma must be valid", isValidTemperatureInput("-,5"))
        assertTrue("Negative leading dot must be valid", isValidTemperatureInput("-.5"))
    }

    @Test
    fun `invalid temperature inputs reject malformed strings`() {
        // Multiple minus signs
        assertFalse("Multiple minus signs must be rejected", isValidTemperatureInput("--"))
        assertFalse("Double minus must be rejected", isValidTemperatureInput("--18"))
        assertFalse("Trailing minus must be rejected", isValidTemperatureInput("18-"))
        assertFalse("Embedded minus must be rejected", isValidTemperatureInput("1-8"))

        // Multiple decimal separators
        assertFalse("Multiple dots must be rejected", isValidTemperatureInput("1.2.3"))
        assertFalse("Multiple commas must be rejected", isValidTemperatureInput("1,2,3"))
        assertFalse("Mixed separators must be rejected", isValidTemperatureInput("1.2,3"))
        assertFalse("Multiple negative commas must be rejected", isValidTemperatureInput("-18,5,2"))

        // Non-numeric characters
        assertFalse("Letters must be rejected", isValidTemperatureInput("abc"))
        assertFalse("Alphanumeric must be rejected", isValidTemperatureInput("-18c"))
        assertFalse("Degree symbol must be rejected", isValidTemperatureInput("-18°"))
        assertFalse("Whitespace must be rejected", isValidTemperatureInput(" -18 "))
        assertFalse("Spaces between digits must be rejected", isValidTemperatureInput("-1 8"))
    }

    @Test
    fun `toReading parses signed decimal and preserves Portuguese comma parsing`() {
        assertEquals(BigDecimal("-18.5"), "-18,5".toReading())
        assertEquals(BigDecimal("-18.5"), "-18.5".toReading())
        assertEquals(BigDecimal("-18"), "-18".toReading())
        assertEquals(BigDecimal("4.5"), "4,5".toReading())
        assertEquals(BigDecimal("4.5"), "4.5".toReading())
        assertEquals(BigDecimal("0.0"), "0,0".toReading())

        // Incomplete or non-numeric strings yield null
        assertNull("-".toReading())
        assertNull("".toReading())
        assertNull("   ".toReading())
        assertNull("abc".toReading())
    }

    @Test
    fun `toggleMinusSign prefixes and removes leading minus sign preserving decimal formats`() {
        // Empty to lone minus and back
        assertEquals("-", toggleMinusSign(""))
        assertEquals("", toggleMinusSign("-"))

        // Positive to negative
        assertEquals("-18", toggleMinusSign("18"))
        assertEquals("-18.5", toggleMinusSign("18.5"))
        assertEquals("-18,5", toggleMinusSign("18,5"))
        assertEquals("-0", toggleMinusSign("0"))
        assertEquals("-,5", toggleMinusSign(",5"))
        assertEquals("-.5", toggleMinusSign(".5"))

        // Negative to positive
        assertEquals("18", toggleMinusSign("-18"))
        assertEquals("18.5", toggleMinusSign("-18.5"))
        assertEquals("18,5", toggleMinusSign("-18,5"))
        assertEquals("0", toggleMinusSign("-0"))
        assertEquals(",5", toggleMinusSign("-,5"))
        assertEquals(".5", toggleMinusSign("-.5"))

        // Unicode minus sign removal
        assertEquals("18.5", toggleMinusSign("−18.5"))
        assertEquals("18,5", toggleMinusSign("−18,5"))
        assertEquals("", toggleMinusSign("−"))
    }
}
