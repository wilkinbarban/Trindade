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
 * The third is the one the screen's shape made necessary. These view models are activity-scoped -- there is
 * no `NavHost` in `main`, so `hiltViewModel()` resolves to the activity -- which means a second read is what
 * a new operator gets, and it has to drop the first answer before it asks again, or the next operator to
 * sign in on the same activity would be shown the previous one's numbers.
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
        assertFalse(model.state.value.failed)
        // One read, and one only: the other two calls on `SystemApi` would have thrown, so a count of one
        // is this test's evidence that the screen asked the question it draws from and nothing else.
        assertEquals(1, api.summaryCalls)
    }

    @Test
    fun `a read that never arrived is a failure, and it is not a summary of zeroes`() {
        val model = DashboardViewModel(DashboardRepository(FakeSystemApi(summaryToReturn = null)))

        model.refresh()

        assertNull(model.state.value.summary)
        assertTrue(model.state.value.failed)
        assertFalse(model.state.value.loading)
    }

    /**
     * The leak the read-on-arrival exists for: a second read never shows the first one's answer.
     *
     * The view model is scoped to the activity, so the state the next operator signs in to is the previous
     * operator's. Dropping the answer before asking again is what stops the two from being on screen
     * together -- so the assertion is made in exactly that window, with the second read held in flight: no
     * summary, and the spinner.
     */
    @Test
    fun `a second read drops the first answer before it asks again`() {
        val api = AnswersOnceThenRefuses()
        val model = DashboardViewModel(DashboardRepository(api))

        model.refresh()
        assertEquals(FakeSystemApi.summary(), model.state.value.summary)
        assertFalse(model.state.value.loading)

        model.refresh()

        // The first answer is gone while the second is in flight -- the state a new operator would
        // otherwise be shown -- and the failure flag is not set yet, because nothing has failed yet.
        assertNull(model.state.value.summary)
        assertTrue(model.state.value.loading)
        assertFalse(model.state.value.failed)
        assertEquals(2, api.summaryCalls)

        // Released, and the first answer still does not come back: what follows a read that did not arrive
        // is the failure, never the numbers the previous one drew.
        api.secondReadMayFinish.complete(Unit)
        assertNull(model.state.value.summary)
    }
}

/**
 * A `SystemApi` whose first summary read answers and whose second refuses, with the second held open.
 *
 * `FakeSystemApi` cannot say this: its answer is a constructor parameter, so every call gets the same one.
 * A sequence is the whole of what the third test needs -- a summary that has arrived, and then a read that
 * does not -- and writing it here, beside the one test that needs it, is cheaper than a second seam in a
 * fake three other files share.
 *
 * [secondReadMayFinish] is what makes the mid-flight state reachable at all. This lane installs an
 * unconfined dispatcher, so a read that answers immediately runs to completion inside `refresh()` and there
 * would be no moment left in which to look at the screen; a read that waits, on the other hand, hands the
 * test the exact state the assertion is about.
 */
private class AnswersOnceThenRefuses : SystemApi {

    var summaryCalls = 0
        private set

    /** Released by the test so the second read can finish; until then the read is in flight. */
    val secondReadMayFinish = CompletableDeferred<Unit>()

    override suspend fun dashboardSummary(): Response<DashboardSummary> {
        summaryCalls += 1
        if (summaryCalls == 1) return Response.success(FakeSystemApi.summary())
        secondReadMayFinish.await()
        return Response.error(500, FakeSystemApi.EMPTY_BODY)
    }

    /** The two calls no test in this file makes, throwing for the reason the shared fake's own throw gives. */
    override suspend fun userOptions(): Response<UserOptionsResponse> = error(FakeSystemApi.NOT_USED)

    override suspend fun health(): Response<HealthResponse> = error(FakeSystemApi.NOT_USED)
}
