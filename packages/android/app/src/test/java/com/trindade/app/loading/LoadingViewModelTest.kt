package com.trindade.app.loading

import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
    fun `a superseded load does not overwrite the newer day, even when it fails`() {
        // The history hands the grid a day to load, and the view model's constructor has already started
        // its own load of today when that happens, so two reads really are in the air at once. Without
        // the token the older answer landing last draws today's rows under the requested day's header,
        // and here it would do worse than that: the older answer is a failure, so the operator would get
        // "sem conexão" over a screen that had loaded fine.
        //
        // The gate is what makes the race reachable at all -- an immediate fake cannot produce it, which
        // is why no test saw it before an independent verifier read the code. The stale read is the one
        // for today, so the requesting date is deliberately a different day.
        val api = FakeLoadingApi(
            schedulesToReturn = listOf(FakeLoadingApi.entry(1, "04:00")),
            gateSchedules = true,
            failForDate = LoadingViewModel.saoPauloToday(),
        )
        val model = viewModel(api)

        model.onDateChange("2026-08-14")
        assertEquals("one request per load", 2, api.scheduleGates.size)

        // The newer read answers first, with the rows of the requested day.
        api.scheduleGates[1].complete(Unit)
        assertEquals("2026-08-14", model.state.value.date)
        assertEquals(1, model.state.value.schedules.size)

        // The superseded one answers after it, as a refusal.
        api.scheduleGates[0].complete(Unit)

        // Nothing of the stale answer: not the rows, not the date, and above all not the unreachable
        // sentence over a day that was read.
        assertEquals("2026-08-14", model.state.value.date)
        assertEquals(1, model.state.value.schedules.size)
        assertEquals(null, model.state.value.message)
        assertEquals(false, model.state.value.loading)
    }

    @Test
    fun `reads the day again when the arrival says the copy may be stale, and not on a plain one`() {
        // The read count is the claim here, and one gate per read is how this fake shows one: every
        // schedule read it is asked for is a gate that exists. The constructor's own read of today is
        // the first, and it is the read the tab's arrival is answered from.
        val api = FakeLoadingApi(
            schedulesToReturn = listOf(FakeLoadingApi.entry(41, "04:00")),
            gateSchedules = true,
        )
        val model = viewModel(api)
        assertEquals("the constructor's own read of today", 1, api.scheduleGates.size)

        // A plain arrival at the tab's own grid: the day it is already showing is not read again. This
        // is the skip the grid has always had, and it is the direction that refuses a fix which reads
        // on every arrival -- such a grid would pass the leg below and this one would catch it.
        model.onDateChange(model.state.value.date)
        assertEquals("a plain arrival for the day already on screen", 1, api.scheduleGates.size)

        // The arrival the edit surface's save leaves behind: the day in hand is the one the editor has
        // just written to, so the caller says so rather than letting the read be skipped. Without the
        // forced read the row the operator moved is drawn from the day as it was before their own edit
        // -- in the slot they moved it out of, which reads as the edit having done nothing at all.
        model.onDateChange(model.state.value.date, force = true)
        assertEquals("the arrival that follows a save", 2, api.scheduleGates.size)
        assertEquals(model.state.value.date, api.lastSchedulesDate)
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
