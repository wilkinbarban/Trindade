package com.trindade.app.ui

import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The formatting the two history screens share.
 *
 * They were written one slice apart, and the second one first imported a helper out of the reports package
 * and copied the other two. Both histories list records by date for the same operators on the same phone, so
 * the arithmetic belongs somewhere neither feature owns: a copy is a second place for it to drift, and an
 * import from `reports` into `loading` is the coupling that makes a shared home worth having.
 *
 * They are pure functions on purpose. A date and a note are formatted here rather than read from a clock or
 * a locale, which is what lets a JVM test pin them without a device -- see `HistoryFormattingTest`.
 */

/**
 * The ISO `YYYY-MM-DD` as the operators write dates, e.g. `18/09/2026`.
 *
 * Reordered rather than formatted, which is exactly what the SPA's own `formatDate` does, and for the same
 * reason: a locale-formatted date changes shape with the phone's settings, and a date is a field here rather
 * than a sentence. A value that is not three parts comes back as it arrived, because this is a display and a
 * malformed date is a contract defect worth seeing rather than crashing over.
 */
internal fun displayDate(iso: String): String {
    val parts = iso.split('-')
    return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else iso
}

/**
 * The ISO `YYYY-MM` as a month in words, e.g. `Setembro de 2026`.
 *
 * Portuguese by name rather than by the device's locale, because the app's copy is Portuguese and a phone
 * set to Spanish would otherwise label the months in a language the rest of the screen is not in.
 */
internal fun displayMonth(iso: String): String {
    val month = runCatching { YearMonth.parse(iso) }.getOrNull() ?: return iso
    val name = month.month
        .getDisplayName(TextStyle.FULL, PORTUGUESE)
        .replaceFirstChar { it.uppercase() }
    return "$name de ${month.year}"
}

/** The one locale these screens format in, which is the language of their own copy. */
private val PORTUGUESE: Locale = Locale.forLanguageTag("pt-BR")

/**
 * The last [count] months as ISO `YYYY-MM`, newest first, ending at [today]'s own month.
 *
 * Takes the date as an argument rather than reading a clock, which is both what makes it testable and what
 * keeps it off the device's timezone: month arithmetic on a `LocalDate` has no zone to consult, so a phone
 * set to the wrong one still offers the same twelve months.
 */
internal fun recentMonths(today: LocalDate, count: Int = 12): List<String> =
    (0 until count).map { YearMonth.from(today).minusMonths(it.toLong()).toString() }

/**
 * The note as one line of a list row, or null when there is nothing to show.
 *
 * Cut at [limit] characters with an ellipsis after them, so the operator can see there is more rather than
 * reading a sentence that stops mid-word. The limit counts the note's own characters and the ellipsis is
 * added after them, which is the same 60 the SPA slices at.
 *
 * A blank note is no note. The SPA's truthiness test would draw a separator with nothing after it for a note
 * that is only spaces, and an empty line under the author's name reads as a rendering fault rather than as
 * an unremarkable absence; so a whitespace-only note collapses to null, the same as an absent one. The note
 * is trimmed, because padding the server stored would otherwise push the author and the note apart on a
 * narrow row.
 */
internal fun notesPreview(notes: String?, limit: Int = 60): String? {
    val text = notes?.trim().orEmpty()
    if (text.isEmpty()) return null
    return if (text.length <= limit) text else text.take(limit) + "…"
}
