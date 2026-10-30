package com.trindade.app.massas

import com.trindade.app.aero.AeroErpMovement
import com.trindade.app.network.MassaIndividualLaunch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The unified table's rules, as the web already proved them.
 *
 * Each test names one rule and asserts the whole row it produces, because the failure this guards against is
 * not a wrong count -- it is a row that appears twice, or a badge that says reconciled while the balance says
 * otherwise. The port is faithful or it is not worth having: two screens of the same day that disagree is the
 * bug the unified table exists to remove.
 */
class MassasUnifiedTest {

    private fun launch(
        id: Int,
        productCode: String = "1192",
        boxes: Int = 2,
        units: Int = 24,
        username: String = "JEFFERSON",
        displayName: String = "Jefferson",
        date: String = "2026-10-30",
    ) = MassaIndividualLaunch(
        id = id,
        launchDate = date,
        date = date,
        productCode = productCode,
        productName = "MASSA DE PASTEL 500G",
        boxes = boxes,
        units = units,
        userId = 1,
        username = username,
        displayName = displayName,
    )

    private fun seat(
        entLan: Int,
        productCode: String = "1192",
        quantity: Double = 24.0,
        operatorName: String = "JEFFERSON",
        entryDate: String = "2026-10-30",
        movementType: String = "E",
    ) = AeroErpMovement(
        entLan = entLan,
        productCode = productCode,
        productDesc = "MASSA DE PASTEL 500G",
        quantity = quantity,
        entryDate = entryDate,
        operatorName = operatorName,
        movementType = movementType,
    )

    private val products = listOf(
        AeroImportProduct(code = "1192", name = "MASSA DE PASTEL 500G", unitsPerBox = 12),
        AeroImportProduct(code = "0800", name = "SACOLA", unitsPerBox = 100),
    )

    @Test
    fun `a launch the ERP also recorded is reconciled and counts once`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = listOf(launch(id = 1)),
                movements = listOf(seat(entLan = 900)),
                products = products,
            ),
        )

        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(MassasRowState.RECONCILED, row.state)
        assertEquals("launch-1", row.key)
        assertEquals(listOf(1), row.launchIds)
        assertTrue(row.entLans.isEmpty())
        assertFalse(row.importable)

        // The seat is the registration and the launch is the work: two boxes, not four.
        assertEquals(MassasBalance(produced = 24, registered = 24, difference = 0), massasBalance(rows))
    }

    @Test
    fun `a launch the ERP did not record is pending and does not count as registered`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = listOf(launch(id = 2)),
                movements = emptyList(),
                products = products,
            ),
        )

        assertEquals(MassasRowState.PENDING_ERP, rows.single().state)
        assertEquals(MassasBalance(produced = 24, registered = 0, difference = -24), massasBalance(rows))
    }

    @Test
    fun `a seat nobody launched here is importable only when it fits whole boxes`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = emptyList(),
                movements = listOf(seat(entLan = 901, quantity = 24.0), seat(entLan = 902, quantity = 30.0)),
                products = products,
            ),
        )

        assertEquals(2, rows.size)
        val whole = rows.first { it.key == "seat-901" }
        val fraction = rows.first { it.key == "seat-902" }
        assertTrue(whole.importable)
        assertEquals(2, whole.boxes)
        assertEquals(listOf(901), whole.entLans)
        assertFalse(fraction.importable)
        assertEquals(2, fraction.boxes)
        // Whole boxes are what an import sends, so the panel can post it without asking anything else.
        assertEquals(AeroImportPlan(productCode = "1192", boxes = 2), aeroImportPlan(seat(901), products))
    }

    @Test
    fun `a supply is filtered out of the table but still importable on its own`() {
        val supply = seat(entLan = 903, productCode = "9999", quantity = 500.0)
        val rows = massasUnifiedRows(
            MassasUnifiedInput(launches = emptyList(), movements = listOf(supply), products = products),
        )

        assertTrue("A supply that is not in the catalogue is not a production row", rows.isEmpty())
        assertEquals(null, aeroImportPlan(supply, products))
    }

    @Test
    fun `a stock exit never becomes a row`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = emptyList(),
                movements = listOf(seat(entLan = 904, movementType = "S")),
                products = products,
            ),
        )
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `a launch of zero boxes is not a row and does not move the balance`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = listOf(launch(id = 3, boxes = 0, units = 0)),
                movements = emptyList(),
                products = products,
            ),
        )
        assertTrue(rows.isEmpty())
        assertEquals(MassasBalance(0, 0, 0), massasBalance(rows))
    }

    @Test
    fun `the operator filter looks at both names on both sides of the day`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = listOf(
                    launch(id = 4, username = "JEFFERSON", displayName = "Jefferson"),
                    launch(id = 5, username = "WILKIN", displayName = "Wilkin"),
                ),
                movements = listOf(seat(entLan = 905, operatorName = "JEFFERSON")),
                products = products,
            ),
            operatorFilter = "jeff",
        )

        assertEquals(listOf("launch-4"), rows.map { it.key })
    }

    @Test
    fun `pending rows come first, then seats, then what is already reconciled`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = listOf(launch(id = 6), launch(id = 7, productCode = "1192", username = "WILKIN")),
                movements = listOf(seat(entLan = 906), seat(entLan = 907, operatorName = "MARCOS")),
                products = products,
            ),
        )

        assertEquals(
            listOf(MassasRowState.PENDING_ERP, MassasRowState.ERP_ONLY, MassasRowState.RECONCILED),
            rows.map { it.state },
        )
    }

    @Test
    fun `an acknowledged pending launch is marked and a reconciled one is not`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = listOf(launch(id = 8), launch(id = 9)),
                movements = listOf(seat(entLan = 908)),
                products = products,
                ackedLaunchIds = setOf(8, 9),
            ),
        )

        // Which of the two identical launches the seat pairs with is the matcher's business: what the row
        // must say is that only the pending one is waiting for the tablet, and the reconciled one waits for
        // nothing.
        val pending = rows.single { it.state == MassasRowState.PENDING_ERP }
        val reconciled = rows.single { it.state == MassasRowState.RECONCILED }
        assertTrue(pending.tabletAcked)
        assertFalse("A reconciled row is not waiting for anything", reconciled.tabletAcked)
    }

    @Test
    fun `reconciledLaunchIds names the launches the ERP already has and nothing else`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = listOf(launch(id = 21), launch(id = 22, productCode = "0800")),
                movements = listOf(seat(entLan = 921)),
                products = products,
            ),
        )

        assertEquals(setOf(21), reconciledLaunchIds(rows))
        // El historial no tiene mitad ERP: no hay nada conciliado que bloquear.
        assertEquals(emptySet<Int>(), reconciledLaunchIds(null))
        assertEquals(emptySet<Int>(), reconciledLaunchIds(emptyList()))
    }

    @Test
    fun `an empty catalogue shows no seats at all`() {
        val rows = massasUnifiedRows(
            MassasUnifiedInput(
                launches = emptyList(),
                movements = listOf(seat(entLan = 909)),
                products = emptyList(),
            ),
        )
        assertTrue("Without a catalogue there is no way to know what is production", rows.isEmpty())
    }
}
