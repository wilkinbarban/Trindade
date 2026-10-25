package com.trindade.app.auth

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two letters the profile header puts in the avatar's circle.
 *
 * It is a pure function and is tested as one: the screen draws whatever this returns, so the interesting
 * cases are the ones a name comes in -- one word, two, more than two, accents, padding, and nothing at
 * all, which is the case that has to fall back to the icon instead of to an empty circle.
 */
class ProfileInitialsTest {

    @Test
    fun `two words give the first letter of each`() {
        assertEquals("OS", profileInitials("Operador Silva"))
    }

    @Test
    fun `a single word gives its first letter only`() {
        assertEquals("M", profileInitials("  maria  "))
    }

    @Test
    fun `more than two words stop at the first two`() {
        assertEquals("AP", profileInitials("ana paula de souza"))
    }

    @Test
    fun `accents are kept and upper-cased`() {
        assertEquals("ÁN", profileInitials("álvaro núñez"))
    }

    @Test
    fun `an empty name gives no letters so the caller can fall back`() {
        assertEquals("", profileInitials(""))
        assertEquals("", profileInitials("   "))
    }
}
