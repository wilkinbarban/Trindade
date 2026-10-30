package com.trindade.app.reports

import com.trindade.app.contract.models.MassasReportDayView
import com.trindade.app.network.BigDecimalSerializer
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * The card used to stay empty because the phone and the server disagreed about the day's shape.
 *
 * The generated contract names the operator `displayName` and its ERP-only flag `onlyErp`; the service answered
 * `name` and `erpOnly` and left `entLans` out. Kotlinx.serialization rejected the whole payload over the missing
 * required fields, the repository turned that into `null`, and the card rendered as an empty day — a wrong answer
 * that looked like a quiet one.
 *
 * This test feeds the exact body the backend produces today through the same [Json] the app builds, so a future
 * field that only one side knows about fails here instead of in the plant.
 */
class ReportDayContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            contextual(BigDecimal::class, BigDecimalSerializer)
        }
    }

    /** Trimmed from `GET /api/massas-launch/report?date=2026-10-30` on a day with one seat reconciled. */
    private val backendBody = """
        {
          "date": "2026-10-30",
          "operators": [
            {
              "operatorKey": "ALICE SILVA",
              "name": "Alice Silva",
              "displayName": "Alice Silva",
              "username": "alice",
              "boxes": 4,
              "units": 40,
              "products": [
                { "code": "1045", "name": "CASEIRA 500G", "boxes": 4, "units": 40 }
              ],
              "erpOnly": false,
              "onlyErp": false,
              "entLans": [9001]
            },
            {
              "operatorKey": "TIAGO",
              "name": "TIAGO",
              "displayName": "TIAGO",
              "username": null,
              "boxes": 3,
              "units": 18,
              "products": [
                { "code": "1047", "name": "DOCE DE LEITE", "boxes": 3, "units": 18 }
              ],
              "erpOnly": true,
              "onlyErp": true,
              "entLans": [9002]
            }
          ],
          "products": [
            { "code": "1045", "name": "CASEIRA 500G", "unitsPerBox": 10, "totalBoxes": 4, "totalUnits": 40 }
          ],
          "totalBoxes": 7,
          "totalUnits": 58,
          "balance": {
            "producedBoxes": 7,
            "producedUnits": 58,
            "registeredBoxes": 7,
            "registeredUnits": 58,
            "differenceUnits": 0,
            "reconciledSeats": 1,
            "pendingSeats": 1
          },
          "reconciledEntLans": [9001]
        }
    """.trimIndent()

    @Test
    fun `the day the backend sends today deserializes instead of collapsing to null`() {
        val day = json.decodeFromString<MassasReportDayView>(backendBody)

        assertEquals("2026-10-30", day.date)
        assertEquals(2, day.operators.size)

        val alice = day.operators.first { it.displayName == "Alice Silva" }
        assertEquals("ALICE SILVA", alice.operatorKey)
        assertEquals("Alice Silva", alice.name)
        assertEquals("alice", alice.username)
        assertFalse(alice.onlyErp)
        assertEquals(listOf(BigDecimal(9001)), alice.entLans)
        assertEquals(0, BigDecimal(40).compareTo(alice.units))

        val tiago = day.operators.first { it.displayName == "TIAGO" }
        assertTrue(tiago.onlyErp)
        assertNull(tiago.username)
        assertEquals(listOf(BigDecimal(9002)), tiago.entLans)

        assertEquals(0, BigDecimal(58).compareTo(day.balance.producedUnits))
        assertEquals(0, BigDecimal(58).compareTo(day.balance.registeredUnits))
        assertEquals(0, BigDecimal.ZERO.compareTo(day.balance.differenceUnits))
        assertEquals(0, BigDecimal.ONE.compareTo(day.balance.reconciledSeats))
        assertEquals(0, BigDecimal.ONE.compareTo(day.balance.pendingSeats))
    }
}
