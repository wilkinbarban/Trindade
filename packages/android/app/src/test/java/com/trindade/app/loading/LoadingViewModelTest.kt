package com.trindade.app.loading

import com.trindade.app.contract.models.SchedulesResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.network.LoadingApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The loading grid's state.
 *
 * The assertion that earns its place is the slots union: the grid draws the configured slots plus any
 * slot an entry already occupies, because a slot removed from settings after entries were made would
 * otherwise hide them -- still on the server, invisible on the screen, and therefore impossible for
 * anyone to remove.
 *
 * The São Paulo date is deliberately not asserted as a value. Its correctness is that it is not the
 * device's date, and a test that computed the expected value the same way the code does would agree
 * with a bug; what is checked here is that it is a date, and the timezone behaviour is named as
 * untestable without an injected clock.
 *
 * The export assertions are about what the operator would get wrong: the text is the server's and is
 * copied verbatim, it is asked for only when wanted, and it is **dropped when the day's entries
 * change**, because the failure mode of leaving it on screen is a WhatsApp message sent with a driver
 * that was just removed.
 *
 * The arrival's read is asserted in both directions, because the rule that decides it cuts both ways.
 * The day already on screen is not read again -- that skip is what keeps an ordinary entry to the tab at
 * one read rather than two, the constructor's own read being that one -- while the arrival that follows
 * the edit surface's save does read it, because the copy in hand is the day as it was *before* the
 * operator's own edit. A grid that simply read on every arrival would pass the second half and fail the
 * first, which is why both are in one leg.
 */
class LoadingViewModelTest {

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: FakeLoadingApi) = LoadingViewModel(LoadingRepository(api))

    @Test
    fun `loads the day's entries and the configured slots`() {
        val api = FakeLoadingApi(schedulesToReturn = listOf(FakeLoadingApi.entry(1, "04:00")))
        val model = viewModel(api)

        assertEquals(1, model.state.value.schedules.size)
        assertEquals(3, model.state.value.timeSlots.size)
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `asks the server for the date it is showing`() {
        val api = FakeLoadingApi()
        val model = viewModel(api)

        // The date drives the request, so a wrong one asks the wrong question rather than showing the
        // right answer in the wrong place.
        assertEquals(model.state.value.date, api.lastSchedulesDate)
        assertEquals(LoadingViewModel.saoPauloToday(), api.lastSchedulesDate)
    }

    @Test
    fun `keeps a slot that settings no longer lists but an entry still occupies`() {
        // 23:00 is not among the configured slots, and the entry sitting in it must still be visible:
        // otherwise it exists on the server and nowhere on the screen.
        val api = FakeLoadingApi(
            schedulesToReturn = listOf(FakeLoadingApi.entry(1, "04:00"), FakeLoadingApi.entry(2, "23:00")),
        )
        val model = viewModel(api)

        assertTrue(model.state.value.slots.contains("23:00"))
        assertEquals("04:00", model.state.value.slots.first())
        assertEquals("23:00", model.state.value.slots.last())
    }

    @Test
    fun `still shows the entries when the slot list cannot be fetched`() {
        // The slots are a convenience; the entries are the point. Failing the whole screen over a
        // missing list would hide work that is on the server.
        val api = FakeLoadingApi(
            schedulesToReturn = listOf(FakeLoadingApi.entry(1, "07:15")),
            timeSlotsToReturn = null,
        )
        val model = viewModel(api)

        assertEquals(listOf("07:15"), model.state.value.slots)
        assertEquals(null, model.state.value.message)
    }

    @Test
    fun `says the server is unreachable when the entries cannot be fetched`() {
        val model = viewModel(FakeLoadingApi(schedulesToReturn = null))

        assertTrue(model.state.value.schedules.isEmpty())
        assertNotNull(model.state.value.message)
    }

    @Test
    fun `counts fleteros per slot and marks the window only beyond the limit`() {
        val api = FakeLoadingApi(
            schedulesToReturn = (1..3).map { FakeLoadingApi.entry(it, "04:00") },
        )
        val model = viewModel(api)

        assertEquals(3, model.state.value.fleteroCountIn("04:00"))
        // Three is the denominator, not an excess.
        assertFalse(model.state.value.isExceeded("04:00"))
    }

    @Test
    fun `leaves a company driver out of the count while still drawing it`() {
        val api = FakeLoadingApi(
            schedulesToReturn = listOf(
                FakeLoadingApi.entry(1, "04:00"),
                FakeLoadingApi.entry(2, "04:00", SchedulesResponseSchedulesInner.DriverType.casa),
            ),
        )
        val model = viewModel(api)

        // Drawn, because it is a row of the grid; not counted, because the counter is about fleteros.
        assertEquals(2, model.state.value.entriesIn("04:00").size)
        assertEquals(1, model.state.value.fleteroCountIn("04:00"))
    }

    @Test
    fun `reports the date in the ISO form the API expects`() {
        val today = LoadingViewModel.saoPauloToday()

        assertTrue("expected an ISO date, got $today", Regex("""\d{4}-\d{2}-\d{2}""").matches(today))
    }

    @Test
    fun `takes the driver type from the chosen driver rather than asking for it`() {
        val api = FakeLoadingApi()
        val model = viewModel(api)
        model.startAdding("04:00")
        model.onDriverSelected(FakeLoadingApi.FLETERO_DRIVER_ID)
        model.confirmAdd()

        // The request cannot contradict the driver it names, because there is no control that could
        // disagree with it.
        assertEquals("fletero", api.createdBody!!.driverType.value)
        assertEquals(FakeLoadingApi.FLETERO_DRIVER_ID, api.createdBody!!.driverId)
        assertEquals(model.state.value.date, api.createdBody!!.scheduleDate)
        assertEquals("04:00", api.createdBody!!.timeSlot)
    }

    @Test
    fun `requires a vehicle for a company driver`() {
        val api = FakeLoadingApi()
        val model = viewModel(api)
        model.startAdding("04:00")
        model.onDriverSelected(FakeLoadingApi.CASA_DRIVER_ID)

        // The server rejects a casa assignment without one, so the form follows the same rule instead
        // of letting the operator discover it as a 400.
        assertTrue(model.state.value.needsVehicle)
        assertFalse(model.state.value.canConfirm)

        model.onVehicleSelected(FakeLoadingApi.VEHICLE_ID)
        assertTrue(model.state.value.canConfirm)
        model.confirmAdd()

        assertEquals("casa", api.createdBody!!.driverType.value)
        assertEquals(FakeLoadingApi.VEHICLE_ID, api.createdBody!!.vehicleId)
    }

    @Test
    fun `does not send a vehicle for an external driver`() {
        val api = FakeLoadingApi()
        val model = viewModel(api)
        model.startAdding("04:00")
        model.onDriverSelected(FakeLoadingApi.CASA_DRIVER_ID)
        model.onVehicleSelected(FakeLoadingApi.VEHICLE_ID)

        // Switching to an external driver has to drop the vehicle, or the request would carry a
        // pairing the operator stopped meaning and the server would take it seriously.
        model.onDriverSelected(FakeLoadingApi.FLETERO_DRIVER_ID)
        assertFalse(model.state.value.needsVehicle)
        model.confirmAdd()

        assertEquals(null, api.createdBody!!.vehicleId)
    }

    @Test
    fun `closes the form and reloads after a successful add`() {
        val model = viewModel(FakeLoadingApi())
        model.startAdding("04:00")
        model.onDriverSelected(FakeLoadingApi.FLETERO_DRIVER_ID)
        model.confirmAdd()

        assertEquals(null, model.state.value.addingToSlot)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `keeps the form open and explains a refused add`() {
        val api = FakeLoadingApi(createResponse = Response.error(409, FakeLoadingApi.EMPTY_BODY))
        val model = viewModel(api)
        model.startAdding("04:00")
        model.onDriverSelected(FakeLoadingApi.FLETERO_DRIVER_ID)
        model.confirmAdd()

        // A duplicate is the operator's to resolve, so the form stays open with what they chose still
        // in it rather than discarding their work.
        assertEquals("04:00", model.state.value.addingToSlot)
        assertNotNull(model.state.value.message)
        assertFalse(model.state.value.busy)
    }

    @Test
    fun `removes an entry and reloads`() {
        val api = FakeLoadingApi(schedulesToReturn = listOf(FakeLoadingApi.entry(7, "04:00")))
        val model = viewModel(api)
        model.deleteEntry(7)

        assertEquals(listOf(7), api.deletedIds)
    }

    @Test
    fun `explains a refused delete rather than reporting a general failure`() {
        val api = FakeLoadingApi(schedulesToReturn = listOf(FakeLoadingApi.entry(7, "04:00")), deleteSucceeds = false)
        val model = viewModel(api)
        model.deleteEntry(7)

        // 403 means the entry belongs to someone else, which is a different thing to say than "it
        // failed" -- one is a rule, the other is a problem to report.
        assertTrue(model.state.value.message!!.lowercase().contains("próprios"))
    }

    @Test
    fun `offers the newly quick-added driver and selects it`() {
        val model = viewModel(FakeLoadingApi())
        model.startAdding("04:00")
        model.quickAddDriver("Novo", "QQQ1111")

        // Selected, because the operator added it in order to use it; leaving them to find it in the
        // list again would be the kind of small friction that makes a form feel broken.
        assertEquals("Novo", model.state.value.selectedDriver?.name)
        assertEquals(3, model.state.value.drivers.size)
    }

    @Test
    fun `a superseded load neither overwrites the newer day nor reports success`() = runTest {
        // Three reads are held: construction, the stale arrival whose completion the route may await,
        // and the newer day that supersedes it. The newer one succeeds first. The stale request then
        // answers successfully too, but its own deferred must remain false: another token winning is
        // not evidence that this token supplied the schedules on screen.
        val api = FakeLoadingApi(
            schedulesToReturn = listOf(FakeLoadingApi.entry(1, "04:00")),
            gateSchedules = true,
        )
        val model = viewModel(api)
        val staleRead = model.onDateChange(model.state.value.date, force = true)!!
        val newerRead = model.onDateChange("2026-08-14")!!
        assertEquals("one request per load", 3, api.scheduleGates.size)

        api.scheduleGates[2].complete(Unit)
        assertTrue("the winning request reports its own success", newerRead.await())
        assertEquals("2026-08-14", model.state.value.date)
        assertEquals(1, model.state.value.schedules.size)

        api.scheduleGates[1].complete(Unit)
        assertFalse("a superseded request cannot spend a stale fact", staleRead.await())
        api.scheduleGates[0].complete(Unit)

        // Nothing of either superseded answer: not the rows, not the date, and no failure message over a
        // day that was read successfully by the winning request.
        assertEquals("2026-08-14", model.state.value.date)
        assertEquals(1, model.state.value.schedules.size)
        assertEquals(null, model.state.value.message)
        assertEquals(false, model.state.value.loading)
    }

    @Test
    fun `a failed forced arrival reports no success and the next forced arrival retries`() = runTest {
        var reads = 0
        val api = object : LoadingApi by FakeLoadingApi() {
            override suspend fun schedules(date: String): Response<SchedulesResponse> {
                reads++
                if (reads == 2) return Response.error(500, FakeLoadingApi.EMPTY_BODY)
                val slot = if (reads == 1) "04:00" else "04:30"
                return Response.success(
                    SchedulesResponse(schedules = listOf(FakeLoadingApi.entry(41, slot))),
                )
            }
        }
        val model = LoadingViewModel(LoadingRepository(api))
        assertEquals("the constructor's own read", 1, reads)

        val failed = model.onDateChange(model.state.value.date, force = true)!!
        assertFalse("a missing schedules answer cannot consume stale", failed.await())
        assertEquals(2, reads)
        assertNotNull(model.state.value.message)

        val retried = model.onDateChange(model.state.value.date, force = true)!!
        assertTrue("the retry that writes schedules may consume stale", retried.await())
        assertEquals(3, reads)
        assertEquals("04:30", model.state.value.schedules.single().timeSlot)

        // The settled ordinary arrival remains the old no-double-read path and has no completion to
        // acknowledge, because it started no request.
        assertEquals(null, model.onDateChange(model.state.value.date))
        assertEquals(3, reads)
    }

    @Test
    fun `does not ask the server for the export until it is requested`() {
        val api = FakeLoadingApi()
        val model = viewModel(api)

        // The export is the one thing on the screen the operator may not want, and it is a call of its
        // own; fetching it with the grid would make every visit pay for it.
        assertEquals(null, model.state.value.exportText)
        assertEquals(null, api.lastExportDate)
    }

    @Test
    fun `renders the export for the day on screen, verbatim`() {
        val api = FakeLoadingApi()
        val model = viewModel(api)
        model.loadExport()

        // The grid's own date rather than a second control, so the text and the entries below it cannot
        // describe different days. Verbatim because the text is the product's output: a client that
        // reformatted it would be a second implementation to keep in agreement with the first.
        assertEquals(model.state.value.date, api.lastExportDate)
        assertEquals(FakeLoadingApi.EXPORT_TEXT, model.state.value.exportText)
        assertFalse(model.state.value.loadingExport)
    }

    @Test
    fun `says so when the server cannot render the export`() {
        val api = FakeLoadingApi(exportToReturn = null)
        val model = viewModel(api)
        model.loadExport()

        // Nothing to copy and nothing shown: an empty copy button would be a silent failure. The date is
        // the only refusable input here and this client always sends a well-formed one, so what is left
        // is an answer that never arrived.
        assertEquals(null, model.state.value.exportText)
        assertNotNull(model.state.value.message)
    }

    @Test
    fun `drops the rendered export once the day's entries change`() {
        val api = FakeLoadingApi(schedulesToReturn = listOf(FakeLoadingApi.entry(7, "04:00")))
        val model = viewModel(api)
        model.loadExport()
        assertNotNull(model.state.value.exportText)

        model.deleteEntry(7)

        // The text describes the day's entries, so after they change it describes a day that no longer
        // exists -- and the failure mode of leaving it on screen is a WhatsApp message sent with a
        // driver that was just removed. It is one tap away, and the tap is the point.
        assertEquals(null, model.state.value.exportText)
    }
}
