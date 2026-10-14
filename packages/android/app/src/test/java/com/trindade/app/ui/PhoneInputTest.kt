package com.trindade.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone field's rules, kept pure so the screens that use them hold no logic of their own.
 *
 * The number is stored the way the operator typed it -- the driver list shows it back to them, and a
 * value that came back reformatted would read as the application having corrected them -- so cleaning
 * only removes what cannot be part of a number: letters, emoji, and any other punctuation, each of
 * which becomes a space, with runs of spaces collapsed and the ends trimmed. A number an operator wrote
 * inside a note keeps its digits: nothing here guesses which digit was meant as an extension, and the
 * field is theirs to keep clean.
 *
 * The number is optional everywhere it appears. An operator who does not have it yet leaves the field
 * empty, the driver is saved the same way, and the actions that need a number do not appear.
 */
class PhoneInputTest {

    @Test
    fun `keeps the parts of a number an operator can write`() {
        assertEquals("+55 (11) 98888-7777", cleanPhone("+55 (11) 98888-7777"))
        assertEquals("11 98888.7777", cleanPhone("11 98888.7777"))
    }

    @Test
    fun `trims what surrounds the number and collapses what is inside it`() {
        assertEquals("11 98888-7777", cleanPhone("  11 98888-7777  "))
        assertEquals("11 98888-7777", cleanPhone("11   98888-7777"))
    }

    @Test
    fun `drops characters that cannot be part of a number`() {
        assertEquals("11 98888-7777 2", cleanPhone("11 98888-7777 ramal 2"))
        assertEquals("11 98888-7777", cleanPhone("📞 11 98888-7777"))
        assertEquals("", cleanPhone("abc"))
    }

    @Test
    fun `answers empty for empty input so an optional field stays optional`() {
        assertEquals("", cleanPhone(""))
        assertEquals("", cleanPhone("   "))
    }

    @Test
    fun `filtering leaves the spacing exactly as typed, so a number can be typed with spaces`() {
        assertEquals("11 ", filterPhoneInput("11 "))
        assertEquals("+55 (11) 98888-7777", filterPhoneInput("+55 (11) 98888-7777"))
    }

    @Test
    fun `filtering drops a character that cannot belong to a number`() {
        assertEquals("1198888", filterPhoneInput("11abc98888"))
        assertEquals("11", filterPhoneInput("📞11"))
    }
}
