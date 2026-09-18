package com.trindade.app.loading

import com.trindade.app.contract.models.SchedulesResponseSchedulesInner

/**
 * The three-fletero indication, computed the way the SPA computes it.
 *
 * **This is a display, not a rule.** The server accepts a fourth fletero by an explicit business
 * decision, and nothing in this file can refuse an assignment: it decides what the counter shows and
 * whether to mark the slot as exceeded. A client that turned this into a validation would be inventing
 * a rule the product deliberately does not have.
 *
 * The window is symmetric: two fleteros count against each other when their slots are less than an
 * hour apart in either direction, so 03:30 and 04:30 fall in the same window while 03:00 and 04:00 do
 * not. The boundary is strict (`< 60`), which is why the tests below check exactly sixty minutes apart
 * rather than something safely inside.
 *
 * Pure, so it is testable without a device or a server. The comparison is against the server's
 * `time_slot` strings rather than the device clock: nothing here depends on what time it is.
 */
object FleteroQuota {

    /** What the counter reads as its denominator. */
    const val LIMIT = 3

    /** How close two slots have to be to share a window, in minutes. */
    const val WINDOW_MINUTES = 60

    /**
     * Minutes since midnight for a `HH:MM` slot, or null when the value is not one.
     *
     * Null rather than a throw, and null rather than zero: a slot that cannot be read must not be
     * counted, and treating it as midnight would put it in a window with the small hours.
     */
    fun minutesOf(timeSlot: String): Int? {
        val parts = timeSlot.split(':')
        if (parts.size != 2) return null

        val hours = parts[0].toIntOrNull() ?: return null
        val minutes = parts[1].toIntOrNull() ?: return null
        if (hours !in 0..23 || minutes !in 0..59) return null

        return hours * 60 + minutes
    }

    /**
     * How many fleteros fall in the window around [timeSlot].
     *
     * [excludeId] is how the edit screen asks "if this entry moved here, how many would there be",
     * which is not the same question as "how many are there now".
     *
     * Only `fletero` entries count. A company driver is a different thing entirely, which is why the
     * vehicle rules mention them separately and why this counter is not a count of the slot's rows.
     */
    fun countInWindow(
        schedules: List<SchedulesResponseSchedulesInner>,
        timeSlot: String,
        excludeId: Int? = null,
    ): Int {
        val target = minutesOf(timeSlot) ?: return 0

        return schedules.count { schedule ->
            if (schedule.driverType != SchedulesResponseSchedulesInner.DriverType.fletero) return@count false
            if (excludeId != null && schedule.id == excludeId) return@count false

            val minutes = minutesOf(schedule.timeSlot) ?: return@count false
            kotlin.math.abs(minutes - target) < WINDOW_MINUTES
        }
    }

    /** Whether the window already holds more than the indication allows. */
    fun isExceeded(
        schedules: List<SchedulesResponseSchedulesInner>,
        timeSlot: String,
    ): Boolean = countInWindow(schedules, timeSlot) > LIMIT
}
