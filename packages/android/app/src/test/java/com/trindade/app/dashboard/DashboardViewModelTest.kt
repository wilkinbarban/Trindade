package com.trindade.app.dashboard

import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.contract.models.HealthResponse
import com.trindade.app.contract.models.UserOptionsResponse
import com.trindade.app.network.SystemApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The dashboard's states, and what a second read does to the first.
 *
 * The pair that earns its place is the first two. Every count in a `DashboardSummary` is a non-nullable
 * `Int`, so a failed read that answered with defaults would be a screen reporting a day of zeroes --
 * "nothing happened today" -- when what actually happened is that the server was never asked. Those two
 * tests have to be read together: one says the summary the server sent is the one that arrives, and the
 * other says a read that produced no answer arrives as nothing rather than as that summary's zeroed-out
 * shape.
 *
 * The third is the one the read-in-flight rule made necessary, and it is about ordering rather than about a
 * flag: two reads can be in the air at once -- these view models are activity-scoped, so a refresh arrives
 * from a screen that can be left and re-entered at any moment -- and the older one landing last would write
 * the last refresh's answer back over the newer one's. The read in flight is cancelled for exactly that, and
 * the test lets the older read finish *after* the newer answer has landed to say so.
 */
class DashboardViewModelTest {

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `the summary the server answered is the one the screen gets`() {
        val summary = FakeSystemApi.summary()
        val api = FakeSystemApi(summaryToReturn = Response.success(summary))

        val model = DashboardViewModel(DashboardRepository(api))

        // Nothing reads on the view model's own construction any more: the screen's arrival is what does,
        // so a test that wants the read has to ask for it the way the screen does.
        model.refresh()

        assertEquals(summary, model.state.value.summary)
        assertFalse(model.state.value.loading)
        // One read, and one only: the other two calls on `SystemApi` would have thrown, so a count of one
        // is this test's evidence that the screen asked the question it draws from and nothing else.
        assertEquals(1, api.summaryCalls)
    }

    @Test
    fun `a read that never arrived is a failure, and it is not a summary of zeroes`() {
        val model = DashboardViewModel(DashboardRepository(FakeSystemApi(summaryToReturn = null)))

        model.refresh()

        // Not loading and no summary *is* the failure -- these two assertions together are the whole of it,
        // which is why the state has no flag of its own to assert and could disagree with them.
        assertNull(model.state.value.summary)
        assertFalse(model.state.value.loading)
    }

    /**
     * Two reads in the air are two answers that can land in either order, and the older one landing last is
     * the whole of the bug: what it writes is the last refresh's answer, coming back after the newer one had
     * already replaced it -- a previous session's summary reappearing over this session's own.
     *
     * The first read is held open, the second answers, and only then is the first let go. Cancelling the read
     * in flight is what `refresh` does about it, and the assertion is made where it would fail without that:
     * after the slow read has had its chance to write.
     */
    @Test
    fun `a slow read that lands last does not overwrite the one after it`() {
        val stale = FakeSystemApi.summary(latestReportId = 1)
        val fresh = FakeSystemApi.summary(latestReportId = 2)
        val api = SlowFirstReadThenAnswers(stale = stale, fresh = fresh)
        val model = DashboardViewModel(DashboardRepository(api))

        // The first read, started and held: it is the older of the two and it has not answered yet.
        model.refresh()
        assertTrue(model.state.value.loading)

        // The second read, which answers at once. It cancels the first on its way in.
        model.refresh()
        assertEquals(fresh, model.state.value.summary)
        assertFalse(model.state.value.loading)
        assertEquals(2, api.summaryCalls)

        // The older read is let go now, after the newer answer has landed. Were it still alive it would
        // write `stale` over `fresh` right here -- which is the ordering this test exists to rule out.
        api.firstReadMayFinish.complete(Unit)
        assertEquals(fresh, model.state.value.summary)
        assertFalse(model.state.value.loading)
    }
}

/**
 * A `SystemApi` whose first summary read is slow and whose second answers at once, so the older read can be
 * let go *after* the newer answer has landed.
 *
 * `FakeSystemApi` cannot say this. Its answer is a constructor parameter, so every call gets the same one --
 * there is no way to give the two reads different answers, and no way to hold one of them open -- and both
 * halves matter here: an ordering test needs two distinguishable answers and a moment in which to release
 * the older one. A sequence of that shape is the whole of what this test needs, and writing it here, beside
 * the one test that needs it, is cheaper than a second seam in a fake three other files share.
 *
 * [firstReadMayFinish] is what makes the ordering reachable at all. This lane installs an unconfined
 * dispatcher, so a read that answers immediately runs to completion inside `refresh()`; a read that waits,
 * on the other hand, is still in flight when the next `refresh()` is called, which is the exact moment the
 * test is about.
 */
private class SlowFirstReadThenAnswers(
    /** What the first, slow read would write if it were ever allowed to answer. */
    private val stale: DashboardSummary,
    /** What the second read answers at once. */
    private val fresh: DashboardSummary,
) : SystemApi {

    var summaryCalls = 0
        private set

    /** Released by the test so the first read can finish; until then that read is in flight. */
    val firstReadMayFinish = CompletableDeferred<Unit>()

    override suspend fun dashboardSummary(): Response<DashboardSummary> {
        summaryCalls += 1
        if (summaryCalls == 1) {
            firstReadMayFinish.await()
            return Response.success(stale)
        }
        return Response.success(fresh)
    }

    /** The two calls no test in this file makes, throwing for the reason the shared fake's own throw gives. */
    override suspend fun userOptions(): Response<UserOptionsResponse> = error(FakeSystemApi.NOT_USED)

    override suspend fun health(): Response<HealthResponse> = error(FakeSystemApi.NOT_USED)
}
