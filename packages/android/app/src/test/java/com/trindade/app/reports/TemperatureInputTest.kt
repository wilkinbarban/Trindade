package com.trindade.app.reports

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sign of a temperature reading, pinned where it is decided.
 *
 * Each case here is a way an operator actually reaches `-` on a keypad that may or may not have a minus
 * key, and the rule is the same for all of them: at most one sign, and only ever at the front.
 */
class TemperatureInputTest {

    @Test
    fun `a minus typed after the digits moves to the front`() {
        assertEquals("-4.5", sanitizeTemperatureInput("4.5-"))
    }

    @Test
    fun `a minus typed between digits does not stay there`() {
        assertEquals("-45", sanitizeTemperatureInput("4-5"))
        assertEquals("-4,5", sanitizeTemperatureInput("4,-5"))
    }

    @Test
    fun `only one sign survives`() {
        assertEquals("-4", sanitizeTemperatureInput("--4"))
        assertEquals("-4,5", sanitizeTemperatureInput("-4,-5-"))
    }

    @Test
    fun `only the first decimal separator survives`() {
        assertEquals("4.56", sanitizeTemperatureInput("4.5.6"))
        assertEquals("4.56", sanitizeTemperatureInput("4.5,6"))
        assertEquals("12,", sanitizeTemperatureInput("12,"))
    }

    @Test
    fun `anything that is not a digit or a separator is dropped`() {
        assertEquals("12", sanitizeTemperatureInput("ab12cd"))
        assertEquals("-3,5", sanitizeTemperatureInput("-3,5 °C"))
        assertEquals("", sanitizeTemperatureInput(""))
        assertEquals("-", sanitizeTemperatureInput("-"))
    }

    @Test
    fun `the sign action flips it and keeps the separator already typed`() {
        assertEquals("-4.5", toggledSign("4.5"))
        assertEquals("4.5", toggledSign("-4.5"))
        assertEquals("-2,", toggledSign("2,"))
        assertEquals("-", toggledSign(""))
    }

    @Test
    fun `the sign action never produces a second sign`() {
        assertEquals("-45", toggledSign("4-5"))
        assertEquals("45", toggledSign("-4-5"))
    }
}
