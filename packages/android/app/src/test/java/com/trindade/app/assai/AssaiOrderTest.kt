package com.trindade.app.assai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order rule, tested where it can be tested: as arithmetic and as text.
 *
 * The unit that matters is the box. An order that does not close whole boxes would reach the gate as a
 * number nobody packed, so the rule refuses it instead of rounding it.
 */
class AssaiOrderTest {

    @Test
    fun `the catalogue carries the nine products and their boxes per box`() {
        assertEquals(9, ASSAI_PRODUCTS.size)
        val byName = ASSAI_PRODUCTS.associateBy { it.name }
        assertEquals(15, byName.getValue("Nhoque 1kg").unitsPerBox)
        assertEquals(80, byName.getValue("Disco 200g").unitsPerBox)
        assertEquals(10, byName.getValue("Rolo 2kg").unitsPerBox)
    }

    @Test
    fun `a quantity that closes whole boxes is exact, and one that does not is not`() {
        val lines = assaiLines(mapOf("Nhoque 1kg" to "30", "Quadrada" to "20"))
        assertTrue(lines.first { it.product == "Nhoque 1kg" }.exact)
        assertEquals(2, lines.first { it.product == "Nhoque 1kg" }.boxes)
        assertFalse(lines.first { it.product == "Quadrada" }.exact)
    }

    @Test
    fun `nothing typed is not a mistake, and text is`() {
        val vacio = assaiLines(emptyMap()).first { it.product == "Nhoque 1kg" }
        assertTrue(vacio.valid)
        assertTrue(vacio.exact)
        assertFalse(vacio.filled)

        val texto = assaiLines(mapOf("Nhoque 1kg" to "abc")).first { it.product == "Nhoque 1kg" }
        assertFalse(texto.valid)
        assertFalse(texto.exact)
        assertTrue(assaiHasError(assaiLines(mapOf("Nhoque 1kg" to "abc"))))
    }

    @Test
    fun `the total adds the boxes of what can be served, and only that`() {
        val lines = assaiLines(mapOf("Nhoque 1kg" to "30", "Rolo 2kg" to "20", "Quadrada" to "20"))
        assertEquals(4, assaiTotalBoxes(lines))
    }

    @Test
    fun `the message carries one line per product and the total at the end`() {
        val mensaje = assaiMessage(assaiLines(mapOf("Nhoque 1kg" to "30", "Rolo 2kg" to "20")))
        assertTrue(mensaje.contains("Nhoque 1kg: 2 caixas"))
        assertTrue(mensaje.contains("Rolo 2kg: 2 caixas"))
        assertTrue(mensaje.contains("Total: 4 caixas"))
        assertFalse(mensaje.contains(" und"))
    }

    @Test
    fun `an empty order says so instead of travelling empty`() {
        assertTrue(assaiMessage(assaiLines(emptyMap())).contains("Nenhum produto"))
    }

    @Test
    fun `the message includes weekday when provided`() {
        val lines = assaiLines(mapOf("Nhoque 1kg" to "30"))
        val comDia = assaiMessage(lines, "Sexta-feira")
        assertTrue(comDia.startsWith("🛒 Pedido Assaí Sexta-feira\n"))

        val semDia = assaiMessage(lines, null)
        assertTrue(semDia.startsWith("🛒 Pedido Assaí\n"))

        val comBranco = assaiMessage(lines, "   ")
        assertTrue(comBranco.startsWith("🛒 Pedido Assaí\n"))

        val vazioComDia = assaiMessage(assaiLines(emptyMap()), "Quinta-feira")
        assertEquals("🛒 Pedido Assaí Quinta-feira\nNenhum produto com quantidade informada.", vazioComDia)
    }

    @Test
    fun `the allowed weekdays list is strictly defined`() {
        assertEquals(listOf("Terça-feira", "Quinta-feira", "Sexta-feira", "Sábado"), ASSAI_WEEKDAYS)
    }

    @Test
    fun `the warning waits for the field to be left and leaves when it is re-entered`() {
        val lines = assaiLines(mapOf("Quadrada" to "20"))
        assertTrue(assaiHasError(lines))

        assertEquals(emptyList<String>(), assaiVisibleErrors(lines, blurred = emptySet(), focused = null))
        assertEquals(emptyList<String>(), assaiVisibleErrors(lines, blurred = setOf("Quadrada"), focused = "Quadrada"))
        assertEquals(listOf("Quadrada"), assaiVisibleErrors(lines, blurred = setOf("Quadrada"), focused = null))
    }
}
