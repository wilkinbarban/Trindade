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
}
