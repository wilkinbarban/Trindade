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
}
