package com.trindade.app.massas

import com.trindade.app.network.MassaIndividualLaunch
import com.trindade.app.network.MassaProductTotal
import com.trindade.app.network.MassasLaunchDayView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MassasLogicTest {

    @Test
    fun brazilianDate_formatsCorrectly() {
        assertEquals("18/05/2025", brazilianDate("2025-05-18"))
        assertEquals("01/01/2026", brazilianDate("2026-01-01"))
        assertEquals("invalid-date", brazilianDate("invalid-date"))
    }

    @Test
    fun standardMassasCatalog_containsExpected10Products() {
        assertEquals(10, STANDARD_MASSAS.size)
        val toko = STANDARD_MASSAS.first { it.code == "1616" }
        assertEquals("MASSA TOKO", toko.name)
        assertEquals(10, toko.unitsPerBox)

        val rolo2kg = STANDARD_MASSAS.first { it.code == "1047" }
        assertEquals("MASSA ROLO 2KG", rolo2kg.name)
        assertEquals(6, rolo2kg.unitsPerBox)

        val disco400 = STANDARD_MASSAS.first { it.code == "60" }
        assertEquals("MASSA DISCO 400G", disco400.name)
        assertEquals(20, disco400.unitsPerBox)
    }

    @Test
    fun formatMassasWhatsAppMessage_whenEmpty_returnsEmptyNoticeWithoutTotalGeral() {
        val emptyView = MassasLaunchDayView(
            date = "2025-05-18",
            products = emptyList(),
            launches = emptyList(),
            totalBoxes = 0,
            totalUnits = 0,
        )

        val message = formatMassasWhatsAppMessage(emptyView)
        val expected = "🍝 *Lançamento de Massas — 18/05/2025*\n\nNenhum lançamento registrado até agora."
        assertEquals(expected, message)
        assertFalse(message.contains("Total Geral"))
    }

    @Test
    fun formatMassasWhatsAppMessage_withLaunches_formatsUnitsAndOperatorsWithoutTotalGeral() {
        val view = MassasLaunchDayView(
            date = "2025-05-18",
            products = listOf(
                MassaProductTotal("1045", "MASSA CASEIRA", 10, totalBoxes = 5, totalUnits = 50),
                MassaProductTotal("1616", "MASSA TOKO", 10, totalBoxes = 2, totalUnits = 20),
                MassaProductTotal("39", "MASSA DISCO 200G", 10, totalBoxes = 0, totalUnits = 0),
            ),
            launches = listOf(
                MassaIndividualLaunch(
                    id = 1,
                    launchDate = "2025-05-18",
                    productCode = "1045",
                    productName = "MASSA CASEIRA",
                    boxes = 5,
                    units = 50,
                    userId = 2,
                    username = "joao",
                    displayName = "João Silva",
                    createdAt = "2025-05-18T10:00:00Z",
                    updatedAt = "2025-05-18T10:00:00Z",
                ),
                MassaIndividualLaunch(
                    id = 2,
                    launchDate = "2025-05-18",
                    productCode = "1616",
                    productName = "MASSA TOKO",
                    boxes = 2,
                    units = 20,
                    userId = 3,
                    username = "maria",
                    displayName = "Maria Souza",
                    createdAt = "2025-05-18T11:00:00Z",
                    updatedAt = "2025-05-18T11:00:00Z",
                ),
                MassaIndividualLaunch(
                    id = 3,
                    launchDate = "2025-05-18",
                    productCode = "1616",
                    productName = "MASSA TOKO",
                    boxes = 1,
                    units = 10,
                    userId = 2,
                    username = "joao",
                    displayName = "João Silva",
                    createdAt = "2025-05-18T12:00:00Z",
                    updatedAt = "2025-05-18T12:00:00Z",
                ),
            ),
            totalBoxes = 8,
            totalUnits = 80,
        )

        val message = formatMassasWhatsAppMessage(view)
        assertTrue(message.startsWith("🍝 *Lançamento de Massas — 18/05/2025*"))
        assertTrue(message.contains("• MASSA CASEIRA: 50 un"))
        assertTrue(message.contains("• MASSA TOKO: 20 un"))
        assertFalse(message.contains("MASSA DISCO 200G"))
        assertTrue(message.contains("*Lançado por:* João Silva, Maria Souza"))
        assertFalse(message.contains("Total Geral"))
    }

    @Test
    fun shouldPollMassas_respectsGuards() {
        assertTrue(shouldPollMassas(isScreenActive = true, isEditing = false, isSubmitting = false))
        assertFalse(shouldPollMassas(isScreenActive = false, isEditing = false, isSubmitting = false))
        assertFalse(shouldPollMassas(isScreenActive = true, isEditing = true, isSubmitting = false))
        assertFalse(shouldPollMassas(isScreenActive = true, isEditing = false, isSubmitting = true))
        assertFalse(shouldPollMassas(isScreenActive = true, isEditing = true, isSubmitting = true))
    }

    @Test
    fun canEditOrDeleteLaunch_enforcesRbac() {
        // Admin can edit any
        assertTrue(canEditOrDeleteLaunch(launchUserId = 10, currentUserId = 5, isAdmin = true))
        // Author can edit own
        assertTrue(canEditOrDeleteLaunch(launchUserId = 5, currentUserId = 5, isAdmin = false))
        // Non-author cannot edit
        assertFalse(canEditOrDeleteLaunch(launchUserId = 10, currentUserId = 5, isAdmin = false))
        // Unauthenticated cannot edit
        assertFalse(canEditOrDeleteLaunch(launchUserId = 10, currentUserId = null, isAdmin = false))
    }
}
