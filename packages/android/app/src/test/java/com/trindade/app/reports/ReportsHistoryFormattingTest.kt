package com.trindade.app.reports

import java.time.LocalDate
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two pieces of the history screen that are arithmetic rather than layout.
 *
 * `recentMonths` and `notesPreview` are pure functions so they can be asserted here without a device and
 * without a Compose test host, which is the whole reason they are not written inline in the screen. What
 * they get wrong is not visible in a screenshot: a month list that walks off a year boundary skips or
 * repeats a month, and a note cut in the wrong place either loses a word or shows one that is not there.
 */
class ReportsHistoryFormattingTest {

    @Test
    fun `recent months are twelve, newest first, in the shape the server validates`() {
        val months = recentMonths(LocalDate.of(2026, 9, 18))

        assertEquals(12, months.size)
        assertEquals("2026-09", months.first())
        assertEquals("2025-10", months.last())
        // Sorted descending is the same as newest first only because the wire's shape sorts
        // chronologically, which is also why these strings are what the server's pattern accepts.
        assertEquals(months.sortedDescending(), months)
        months.forEach { month ->
            assertTrue("$month is not YYYY-MM", Regex("^\\d{4}-\\d{2}$").matches(month))
        }
    }

    @Test
    fun `recent months cross a year boundary without skipping or repeating one`() {
        // The case a naive `monthValue - i` gets wrong by counting backwards through January into
        // months that do not exist.
        assertEquals(
            listOf("2026-01", "2025-12", "2025-11"),
            recentMonths(LocalDate.of(2026, 1, 5), count = 3),
        )
    }

    @Test
    fun `recent months do not depend on the device timezone`() {
        val today = LocalDate.of(2026, 9, 18)
        val default = TimeZone.getDefault()
        try {
            // UTC+14 is already into the next day for part of every day, and UTC-11 is still in the one
            // before. A helper that read the clock instead of its argument would answer differently for
            // each of them; this one takes the date it is given and has no zone to consult.
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val east = recentMonths(today)

            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Midway"))
            val west = recentMonths(today)

            assertEquals(listOf("2026-09", "2026-08", "2026-07"), east.take(3))
            assertEquals(east, west)
        } finally {
            TimeZone.setDefault(default)
        }
    }

    @Test
    fun `a note that is not there stays absent`() {
        assertNull(notesPreview(null))
    }

    @Test
    fun `a short note is shown as it was written`() {
        assertEquals("Limpeza feita, tudo em ordem", notesPreview("Limpeza feita, tudo em ordem"))
    }

    @Test
    fun `a note at the limit exactly is not cut`() {
        val note = "a".repeat(60)

        assertEquals(note, notesPreview(note))
    }

    @Test
    fun `a long note is cut at the limit with an ellipsis after it`() {
        val note = "b".repeat(200)

        val preview = notesPreview(note)!!

        // The limit counts the note's own characters and the ellipsis comes after them, which is the same
        // 60 the SPA slices at; the extra character is what tells the operator there is more.
        assertEquals(note.take(60) + "…", preview)
        assertEquals(61, preview.length)
        assertTrue(preview.endsWith("…"))
    }

    @Test
    fun `a caller can set its own limit`() {
        assertEquals("Limp…", notesPreview("Limpeza geral", limit = 4))
    }

    @Test
    fun `a blank note is no note`() {
        // The case worth having: the SPA's truthiness test would draw the separator with nothing after it,
        // and a note of spaces would put an empty line under the author's name on a narrow row.
        assertNull(notesPreview(""))
        assertNull(notesPreview("   "))
        assertNull(notesPreview("\n\t"))
    }
}
