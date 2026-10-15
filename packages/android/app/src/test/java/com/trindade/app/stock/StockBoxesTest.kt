package com.trindade.app.stock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule the counter applies before anything travels, tested where it can be tested: as a decision.
 *
 * The cases mirror the web's, one for one, because the two applications answer the same operator and an
 * order that is a mistake on a phone cannot be correct on a desk.
 */
class StockBoxesTest {

    @Test
    fun `an empty field is not a mistake, it is a field nobody touched`() {
        assertFalse(boxesInvalid(""))
        assertFalse(boxesInvalid("   "))
    }

    @Test
    fun `what is not a positive whole number is not a quantity`() {
        assertTrue(boxesInvalid("0"))
        assertTrue(boxesInvalid("-2"))
        assertTrue(boxesInvalid("2.5"))
        assertTrue(boxesInvalid("abc"))
        assertFalse(boxesInvalid("3"))
        assertFalse(boxesInvalid(" 4 "))
    }

    @Test
    fun `the add button is off with nothing to add or with something that cannot be added`() {
        assertTrue(addDisabled(""))
        assertTrue(addDisabled("   "))
        assertTrue(addDisabled("0"))
        assertTrue(addDisabled("x"))
        assertFalse(addDisabled("3"))
    }

    @Test
    fun `no warning while the field is in focus, and one once it is left`() {
        val cantidades = mapOf("787" to "0")
        assertEquals(emptyList<String>(), visibleBoxErrors(cantidades, blurred = emptySet(), focused = null))
        assertEquals(emptyList<String>(), visibleBoxErrors(cantidades, blurred = setOf("787"), focused = "787"))
        assertEquals(listOf("787"), visibleBoxErrors(cantidades, blurred = setOf("787"), focused = null))
    }

    @Test
    fun `an empty field is never a visible warning, even after being left`() {
        assertEquals(emptyList<String>(), visibleBoxErrors(mapOf("787" to ""), blurred = setOf("787"), focused = null))
    }

    @Test
    fun `only the wrong products are named, and in order`() {
        val cantidades = mapOf("787" to "0", "1800" to "3", "1047" to "abc")
        assertEquals(
            listOf("1047", "787"),
            visibleBoxErrors(cantidades, blurred = setOf("787", "1800", "1047"), focused = null)
        )
    }
}
