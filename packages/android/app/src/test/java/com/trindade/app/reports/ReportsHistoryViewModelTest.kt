package com.trindade.app.reports

import com.trindade.app.contract.models.ReportHistoryResponse
import com.trindade.app.contract.models.ScheduleHistoryResponsePagination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The history's state and, more importantly, the requests it makes.
 *
 * Most of these assertions are about what the view model asked for rather than about what it shows,
 * because the mistakes worth catching here are invisible in the state: a filter tidied from null into
 * the empty string is a 400 the server answers for a client that meant "no filter", and a reload that
 * asks for the wrong page looks exactly like one that asked for the right one until the operator
 * notices the rows are not the ones they were reading. `FakeReportsApi.historyQueries` records the
 * sequence for that reason.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` runs on Main, which a JVM test has no
 * implementation of until one is installed.
 */
class ReportsHistoryViewModelTest {

    private lateinit var api: FakeReportsApi

    @Before
    fun installMainDispatcher() {
        // Unconfined so the coroutines run as they are launched and the assertions need no manual
        // advancing, which keeps the tests about the state rather than about the scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakeReportsApi()
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: FakeReportsApi = this.api) = ReportsHistoryViewModel(ReportsRepository(api))

    @Test
    fun `loads the first page with no filter set, and sends null rather than an empty string`() {
        viewModel()

        // Null and not `""`: the server validates both filters against a pattern, so `date=` is a 400
        // answering a client that meant "no filter" -- and Retrofit omits a null parameter, which is
        // the request that asks for the whole history.
        assertNull(api.lastHistoryQuery?.date)
        assertNull(api.lastHistoryQuery?.month)
        assertEquals(ReportsHistoryQuery(date = null, month = null, page = 1, pageSize = null), api.lastHistoryQuery)
    }

    @Test
    fun `picking a day clears the month and returns to the first page`() {
        val api = FakeReportsApi(historyItemCount = 61)
        val model = viewModel(api)
        model.next()
        assertEquals(2, api.lastHistoryQuery?.page)

        model.onDateSelected("2026-09-18")

        assertEquals("2026-09-18", model.state.value.date)
        assertNull("the two filters are exclusive, so the month has to go", model.state.value.month)
        // Page one, not the page that was on screen: the filtered history is a different list, and
        // page 2 of it may not exist at all.
        assertEquals(ReportsHistoryQuery(date = "2026-09-18", month = null, page = 1, pageSize = null), api.lastHistoryQuery)
    }

    @Test
    fun `picking a month clears the day and returns to the first page`() {
        val api = FakeReportsApi(historyItemCount = 61)
        val model = viewModel(api)
        model.onDateSelected("2026-09-18")
        model.next()

        model.onMonthSelected("2026-09")

        assertNull("the two filters are exclusive, so the day has to go", model.state.value.date)
        assertEquals("2026-09", model.state.value.month)
        assertEquals(ReportsHistoryQuery(date = null, month = "2026-09", page = 1, pageSize = null), api.lastHistoryQuery)
    }

    @Test
    fun `clearing the filters asks for no filter again, from the first page`() {
        val api = FakeReportsApi(historyItemCount = 61)
        val model = viewModel(api)
        model.onMonthSelected("2026-09")
        model.next()

        model.clearFilters()

        assertNull(model.state.value.date)
        assertNull(model.state.value.month)
        assertEquals(ReportsHistoryQuery(date = null, month = null, page = 1, pageSize = null), api.lastHistoryQuery)
    }

    @Test
    fun `pages forward and back within what the server said there is`() {
        val api = FakeReportsApi(historyItemCount = 61)
        val model = viewModel(api)

        assertEquals(1, model.state.value.page)
        assertEquals(3, model.state.value.totalPages)
        assertEquals(61, model.state.value.total)
        assertEquals(false, model.state.value.canGoPrevious)
        assertEquals(true, model.state.value.canGoNext)

        model.next()
        assertEquals(2, model.state.value.page)
        assertEquals(2, api.lastHistoryQuery?.page)
        assertEquals(true, model.state.value.canGoPrevious)
        assertEquals(true, model.state.value.canGoNext)

        model.previous()
        assertEquals(1, model.state.value.page)
        assertEquals(1, api.lastHistoryQuery?.page)
    }

    @Test
    fun `refuses to page below the first or past the last`() {
        val api = FakeReportsApi(historyItemCount = 61)
        val model = viewModel(api)

        model.previous()
        assertEquals("page 1 has nothing behind it", 1, api.historyQueries.size)

        repeat(4) { model.next() }
        // Two pages exist above the first, so only two of those four were asked for.
        assertEquals(3, api.historyQueries.size)
        assertEquals(3, model.state.value.page)
        assertEquals(false, model.state.value.canGoNext)
    }

    @Test
    fun `an empty history is a single page with neither direction offered`() {
        val empty = ReportHistoryResponse(
            items = emptyList(),
            pagination = ScheduleHistoryResponsePagination(page = 1, pageSize = 30, total = 0, totalPages = 0),
        )

        val model = viewModel(FakeReportsApi(historyToReturn = empty))

        // The server answers 0 pages when there is nothing, and 0 is not clamped to 1 here: the only
        // thing that reads it wants to know whether another page exists.
        assertEquals(0, model.state.value.totalPages)
        assertEquals(0, model.state.value.total)
        assertEquals(false, model.state.value.canGoPrevious)
        assertEquals(false, model.state.value.canGoNext)
    }

    @Test
    fun `a successful deletion reloads the page on screen rather than the first one`() {
        // Two pages, the second holding ten: deleting one of them leaves the page worth reading.
        val api = FakeReportsApi(historyItemCount = 40)
        val model = viewModel(api)

        model.next()
        assertEquals(10, model.state.value.items.size)

        model.delete(id = 40)

        assertEquals(listOf(40), api.deletedIds)
        assertEquals("the page the operator was reading", 2, api.lastHistoryQuery?.page)
        assertEquals(2, model.state.value.page)
        assertEquals(9, model.state.value.items.size)
        assertEquals(false, model.state.value.busy)
    }

    @Test
    fun `a deletion that empties the last page steps back to the one that still has rows`() {
        // 31 reports: page 2 holds exactly one, so deleting it empties the page behind it.
        val api = FakeReportsApi(historyItemCount = 31)
        val model = viewModel(api)

        model.next()
        assertEquals(listOf(31), model.state.value.items.map { it.id })

        model.delete(id = 31)

        // Asserted as the sequence of pages asked for, because the settled state alone cannot show that
        // the now-empty page was noticed at all: the first load, the move to page 2, the reload of page
        // 2 after the deletion, and the step back to page 1. A blank screen still labelled "Página 2 de
        // 2" is what this test exists to prevent.
        assertEquals(listOf(1, 2, 2, 1), api.historyQueries.map { it.page })
        assertEquals(1, model.state.value.page)
        assertEquals(30, model.state.value.items.size)
        assertEquals(false, model.state.value.loading)
    }

    @Test
    fun `walks back page by page when more than one page empties at once`() {
        // The step-back is recursive because a single deletion is not the only way a page empties: another
        // operator can empty two of them while this screen sits on the last one. Sixty-one reports are
        // three pages, so page 3 holds one row and page 2 holds thirty.
        val api = FakeReportsApi(historyItemCount = 61)
        val model = viewModel(api)
        model.next()
        model.next()
        assertEquals(3, model.state.value.page)

        // Everything above page one goes away between the read and the reload.
        api.remainingHistoryItems = 0
        model.delete(id = 61)

        // The sequence is the assertion, and not the settled state: page 1 with an empty list looks
        // identical whether the walk happened or the code simply abandoned page 3, and the difference is
        // the two requests that prove the now-empty pages were noticed on the way down.
        assertEquals(listOf(1, 2, 3, 3, 2, 1), api.historyQueries.map { it.page })
        assertEquals(1, model.state.value.page)
        assertTrue(model.state.value.items.isEmpty())
        assertEquals(false, model.state.value.loading)
    }

    @Test
    fun `a read that never answered claims no page`() {
        // The footer and the empty sentence are statements about a history that was read. On a first read
        // that failed there is no page: printing "Página 1 de 1 (0 registros)" under the unreachable
        // sentence would report a network fault as a fact about the reports, which is the mistake the
        // sentence itself exists to avoid.
        val model = viewModel(FakeReportsApi(historyToReturn = null))

        assertEquals(false, model.state.value.loaded)
        assertEquals(0, model.state.value.total)
        assertTrue(model.state.value.items.isEmpty())
        assertNotEquals(null, model.state.value.message)
    }

    @Test
    fun `a deactivation reloads the page and leaves the report in the history`() {
        val api = FakeReportsApi(historyItemCount = 31)
        val model = viewModel(api)

        model.deactivate(id = 1)

        assertEquals(listOf(1), api.deactivatedIds)
        assertEquals(listOf(1, 1), api.historyQueries.map { it.page })
        // Still a report: a deactivated one stays in the history, which is the whole difference between
        // deactivating and deleting.
        assertEquals(31, model.state.value.total)
        assertEquals(false, model.state.value.busy)
    }

    @Test
    fun `offers nothing for a report whose lifecycle flags are absent`() {
        // The contract marks the five flags optional, so the generated type carries them as `Boolean?`.
        // Read as `!= false`, an absent flag would be permission, and the screen would draw an action
        // whose only possible answer is the 403 the flag was there to report.
        val api = FakeReportsApi(historyToReturn = singleReport(canDeactivate = null, canDelete = null))
        val model = viewModel(api)

        model.deactivate(id = 1)
        model.delete(id = 1)

        assertEquals(emptyList<Int>(), api.deactivatedIds)
        assertEquals(emptyList<Int>(), api.deletedIds)
        assertEquals(false, model.state.value.busy)
    }

    @Test
    fun `withholds one action without withholding the other`() {
        // A refusal the server would not have given: `canDeactivate` false means an administrator-only
        // route this caller cannot use, and it says nothing about the deletion, which is offered on its
        // own flag.
        val api = FakeReportsApi(historyToReturn = singleReport(canDeactivate = false, canDelete = true))
        val model = viewModel(api)

        model.deactivate(id = 1)
        model.delete(id = 1)

        assertEquals(emptyList<Int>(), api.deactivatedIds)
        assertEquals(listOf(1), api.deletedIds)
    }

    @Test
    fun `says a 403 in its own words, and a 404 is not the same sentence`() {
        val forbiddenApi = FakeReportsApi(deactivateResponse = Response.error(403, EMPTY_BODY))
        val forbidden = viewModel(forbiddenApi)
        forbidden.deactivate(id = 1)

        val goneApi = FakeReportsApi(deleteResponse = Response.error(404, EMPTY_BODY))
        val gone = viewModel(goneApi)
        gone.delete(id = 1)

        val refused = forbidden.state.value.message
        val missing = gone.state.value.message

        assertNotEquals("a refusal and an absence are not the same problem", refused, missing)
        assertTrue("the server checks the role: $refused", refused!!.contains("administrador", ignoreCase = true))
        assertTrue("the report is not there any more: $missing", missing!!.contains("existe", ignoreCase = true))

        // A refusal is an answer, not a failure: the list on screen is still what the server last said,
        // so neither call reloads it and neither leaves the rows half-disabled.
        assertEquals(1, forbiddenApi.historyQueries.size)
        assertEquals(1, goneApi.historyQueries.size)
        assertEquals(false, forbidden.state.value.busy)
        assertEquals(false, gone.state.value.busy)
        assertEquals(1, forbidden.state.value.items.size)
    }

    @Test
    fun `says the server was not reached rather than showing an empty history`() {
        val model = viewModel(FakeReportsApi(historyToReturn = null))

        // The list is empty and that is the danger: an empty history drawn without a word about the
        // failed read would report a network fault as a fact about the reports.
        assertEquals(true, model.state.value.items.isEmpty())
        assertEquals(false, model.state.value.loading)
        assertEquals(ReportsHistoryViewModel.UNREACHABLE, model.state.value.message)
    }

    /** One report with the flags a test wants, so an absent flag can be the point of a test. */
    private fun singleReport(canDeactivate: Boolean?, canDelete: Boolean?) = ReportHistoryResponse(
        items = listOf(
            FakeReportsApi.historyItem(id = 1).copy(canDeactivate = canDeactivate, canDelete = canDelete),
        ),
        pagination = ScheduleHistoryResponsePagination(page = 1, pageSize = 30, total = 1, totalPages = 1),
    )
}

private val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())
