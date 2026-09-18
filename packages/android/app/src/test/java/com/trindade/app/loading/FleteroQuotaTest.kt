package com.trindade.app.loading

import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The counter's arithmetic, which is the half of D4b that can be tested without a screen.
 *
 * The boundary is the part worth testing. "Rolling 60-minute window" reads as approximately an hour
 * and the implementation is strictly less than one, so two slots exactly an hour apart are in
 * different windows -- and a client that guessed the other reading would show a four where the
 * product shows a three.
 */
class FleteroQuotaTest {

    @Test
    fun `reads a slot as minutes since midnight`() {
        assertEquals(0, FleteroQuota.minutesOf("00:00"))
        assertEquals(240, FleteroQuota.minutesOf("04:00"))
        assertEquals(263, FleteroQuota.minutesOf("04:23"))
        assertEquals(1439, FleteroQuota.minutesOf("23:59"))
    }

    @Test
    fun `refuses a slot that is not a time`() {
        // Null rather than zero: a slot that cannot be read must not be counted, and reading it as
        // midnight would put it in a window with the small hours.
        assertNull(FleteroQuota.minutesOf(""))
        assertNull(FleteroQuota.minutesOf("banana"))
        assertNull(FleteroQuota.minutesOf("4:00:00"))
        assertNull(FleteroQuota.minutesOf("24:00"))
        assertNull(FleteroQuota.minutesOf("04:60"))
    }

    @Test
    fun `counts fleteros within the hour either side`() {
        val schedules = listOf(
            fletero(1, "03:30"),
            fletero(2, "04:30"),
            fletero(3, "05:59"),
        )

        // 03:30 and 04:30 are both inside the window around 04:00; 05:59 is 119 minutes away.
        assertEquals(2, FleteroQuota.countInWindow(schedules, "04:00"))
    }

    @Test
    fun `puts slots exactly an hour apart in different windows`() {
        val schedules = listOf(fletero(1, "03:00"), fletero(2, "05:00"))

        // Strictly less than 60 minutes, so an hour apart does not count -- in either direction.
        assertEquals(0, FleteroQuota.countInWindow(schedules, "04:00"))
        assertEquals(1, FleteroQuota.countInWindow(schedules, "03:00"))
        assertEquals(0, FleteroQuota.countInWindow(schedules, "02:00"))
    }

    @Test
    fun `counts a slot against itself`() {
        // The counter for a slot includes an entry already sitting in it, which is what makes the
        // figure the operator reads match what is on the screen.
        assertEquals(1, FleteroQuota.countInWindow(listOf(fletero(1, "04:00")), "04:00"))
    }

    @Test
    fun `leaves out company drivers`() {
        val schedules = listOf(
            fletero(1, "04:00"),
            casa(2, "04:30"),
            casa(3, "04:30"),
        )

        // A company driver is a different thing entirely; counting them would show a three where the
        // product shows a one.
        assertEquals(1, FleteroQuota.countInWindow(schedules, "04:00"))
    }

    @Test
    fun `can exclude one entry, which is how an edit asks its question`() {
        val schedules = listOf(fletero(1, "04:00"), fletero(2, "04:30"))

        // "If this one moved away, how many remain" is a different question from "how many are there".
        assertEquals(1, FleteroQuota.countInWindow(schedules, "04:00", excludeId = 1))
    }

    @Test
    fun `marks the window exceeded only beyond the limit`() {
        val three = (1..3).map { fletero(it, "04:00") }
        val four = (1..4).map { fletero(it, "04:00") }

        // Three is the limit, not an excess: this is the indication's denominator.
        assertFalse(FleteroQuota.isExceeded(three, "04:00"))
        assertTrue(FleteroQuota.isExceeded(four, "04:00"))
    }

    @Test
    fun `counts nothing for a slot it cannot read`() {
        val schedules = listOf(fletero(1, "04:00"))

        assertEquals(0, FleteroQuota.countInWindow(schedules, "nonsense"))
        assertFalse(FleteroQuota.isExceeded(schedules, "nonsense"))
    }

    private fun fletero(id: Int, slot: String) = schedule(id, slot, SchedulesResponseSchedulesInner.DriverType.fletero)

    private fun casa(id: Int, slot: String) = schedule(id, slot, SchedulesResponseSchedulesInner.DriverType.casa)

    private fun schedule(
        id: Int,
        slot: String,
        type: SchedulesResponseSchedulesInner.DriverType,
    ) = SchedulesResponseSchedulesInner(
        id = id,
        scheduleDate = "2026-09-18",
        timeSlot = slot,
        driverType = type,
        driverId = id,
        driverName = "driver $id",
        licensePlate = null,
        vehicleId = null,
        vehicleDescription = null,
        vehiclePlate = null,
        userId = 1,
        createdAt = "2026-09-18T10:00:00Z",
        updatedAt = "2026-09-18T10:00:00Z",
        isActive = true,
        // The counter does not read the permission flags, so their values here are arbitrary: a test
        // that made them meaningful would be testing something this object does not do.
        readOnly = false,
        canEdit = true,
        canDeactivate = true,
        canDelete = true,
        creator = null,
    )
}
