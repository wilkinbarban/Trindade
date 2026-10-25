package com.trindade.app.stock

import java.time.LocalDate
import java.time.ZoneOffset

/**
 * How far back the history's calendar lets an operator go.
 *
 * Thirty days is the window the screen offers, and it is the screen's rule rather than the server's: the
 * endpoint will answer for any date it has. The point is not secrecy but attention -- a count is corrected
 * the morning after, or the week after at the latest, and an operator scrolling back a year in a calendar is
 * looking for the wrong thing.
 */
internal const val STOCK_HISTORY_WINDOW_DAYS = 30L

/**
 * The oldest day the calendar accepts, in the UTC milliseconds `DatePicker` speaks.
 *
 * Both bounds are built from the same date and the same zone, so they cannot disagree by a day at the edges
 * -- which is exactly the bug a "now minus 30 days" subtraction has in a zone west of Greenwich.
 */
internal fun stockHistoryMinMillis(today: LocalDate): Long =
    today.minusDays(STOCK_HISTORY_WINDOW_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

/** The newest day the calendar accepts: today, which is not in the future even if the count is. */
internal fun stockHistoryMaxMillis(today: LocalDate): Long =
    today.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
