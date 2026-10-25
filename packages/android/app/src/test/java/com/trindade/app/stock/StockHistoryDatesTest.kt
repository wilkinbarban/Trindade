package com.trindade.app.stock

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The window the history's calendar offers, pinned at its two edges.
 *
 * The edges are where this goes wrong: a "now minus 30 days" subtraction lands mid-day and can refuse the
 * day it meant to offer, and a minimum built in one zone against a maximum built in another drifts by a day
 * twice a year. Both bounds here come from the same date and the same zone.
 */
class StockHistoryDatesTest {

    private val today = LocalDate.of(2026, 10, 24)

    @Test
    fun `the newest day offered is today`() {
        val max = stockHistoryMaxMillis(today)
        assertEquals(today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), max)
    }

    @Test
    fun `the oldest day offered is thirty days back`() {
        val min = stockHistoryMinMillis(today)
        assertEquals(today.minusDays(30).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), min)
        assertEquals(30L * 24 * 60 * 60 * 1000, stockHistoryMaxMillis(today) - min)
    }

    @Test
    fun `the window crosses a month boundary without losing a day`() {
        val firstOfMarch = LocalDate.of(2026, 3, 1)
        assertEquals(
            LocalDate.of(2026, 1, 30),
            java.time.Instant.ofEpochMilli(stockHistoryMinMillis(firstOfMarch))
                .atZone(ZoneOffset.UTC).toLocalDate(),
        )
    }
}
