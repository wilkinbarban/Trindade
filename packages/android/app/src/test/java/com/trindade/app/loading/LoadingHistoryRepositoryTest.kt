package com.trindade.app.loading

import com.trindade.app.contract.models.ScheduleHistoryResponse
import com.trindade.app.contract.models.ScheduleHistoryResponsePagination
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The loading history read, its pagination, and the two dates each item carries.
 *
 * The filters behave exactly as the report history's do, and the assertions are the same ones for the
 * same reason: an absent filter and an empty filter are different requests here too, because both
 * endpoints share one query schema and one server-side regex. What is written separately rather than
 * shared is the last assertion, which is about this endpoint only: the server computes `loading_date`
 * from `batch_date`, and this layer's job is to carry that computation and not repeat it.
 *
 * `batch_date` is the `schedule_date` the entries were created under, and it is also the key the
 * endpoint groups by. `loading_date` is the load's own date, which the server answers as the batch
 * date plus one day except on a Friday, where it answers the batch date itself. Recomputing that here
 * would be a second implementation of a rule that lives in SQL, and it would be wrong every Friday.
 */
class LoadingHistoryRepositoryTest {

    private fun repository(api: FakeLoadingApi = FakeLoadingApi()) = LoadingRepository(api)

    @Test
    fun `asks for no filter at all when the caller set none`() = runTest {
        val api = FakeLoadingApi()

        repository(api).history(date = null, month = null, page = null, pageSize = null)

        // All four null and not one empty string: a null page or pageSize asks the server for its own
        // default of 1 and 30, and a null filter means the whole history rather than a 400.
        assertEquals(
            LoadingHistoryQuery(date = null, month = null, page = null, pageSize = null),
            api.lastHistoryQuery,
        )
    }

    @Test
    fun `passes an empty filter through as empty rather than as no filter`() = runTest {
        val api = FakeLoadingApi()

        // An empty date is not a missing date, and this layer has no business deciding which the
        // caller meant: the server rejects the empty one against `^\d{4}-\d{2}-\d{2}$` and the screen
        // is the place that has to stop sending it.
        repository(api).history(date = "", month = "", page = null, pageSize = null)

        assertEquals(
            LoadingHistoryQuery(date = "", month = "", page = null, pageSize = null),
            api.lastHistoryQuery,
        )
    }

    @Test
    fun `passes the page and the page size through as asked`() = runTest {
        val api = FakeLoadingApi()

        // The server's maximum, sent unchanged, and its own default left to the server when the
        // caller has no page in mind.
        repository(api).history(date = null, month = "2026-06", page = 2, pageSize = 100)

        assertEquals(
            LoadingHistoryQuery(date = null, month = "2026-06", page = 2, pageSize = 100),
            api.lastHistoryQuery,
        )
    }

    @Test
    fun `answers null when the server refuses the read`() = runTest {
        // Null rather than an empty envelope: a screen that could not tell "no batches that month"
        // from "the server refused" would render an empty history over a 500 and call it a fact.
        val answer = repository(FakeLoadingApi(historyToReturn = null))
            .history(date = null, month = null, page = null, pageSize = null)

        assertNull(answer)
    }

    @Test
    fun `answers the envelope with its pagination rather than the items alone`() = runTest {
        val page = ScheduleHistoryResponse(
            items = listOf(FakeLoadingApi.historyItem(totalLoadings = 4, isActive = false)),
            pagination = ScheduleHistoryResponsePagination(page = 1, pageSize = 30, total = 62, totalPages = 3),
        )

        val answer = repository(FakeLoadingApi(historyToReturn = page))
            .history(date = null, month = null, page = 1, pageSize = 30)

        // The pagination is the half of the answer a bare list could not carry: the batches group by
        // date, so without `totalPages` the screen cannot tell a full page from the last one.
        assertEquals(62, answer?.pagination?.total)
        assertEquals(3, answer?.pagination?.totalPages)
        assertEquals(4, answer?.items?.single()?.totalLoadings)
        assertEquals(false, answer?.items?.single()?.isActive)
    }

    @Test
    fun `carries both of the server's dates, including a Friday batch that loads the same day`() = runTest {
        // 2026-06-26 is a Friday, and the server answers that batch date twice rather than naming the
        // Saturday. The pair is the proof that this layer repeats the server's computation instead of
        // deriving one of its own -- a client that added a day would turn the Friday into a Saturday,
        // and a test that only used an ordinary date would not notice.
        val page = ScheduleHistoryResponse(
            items = listOf(
                FakeLoadingApi.historyItem(batchDate = "2026-06-26", loadingDate = "2026-06-26"),
                FakeLoadingApi.historyItem(batchDate = "2026-06-27", loadingDate = "2026-06-28"),
            ),
            pagination = ScheduleHistoryResponsePagination(page = 1, pageSize = 30, total = 2, totalPages = 1),
        )

        val answer = repository(FakeLoadingApi(historyToReturn = page))
            .history(date = null, month = null, page = null, pageSize = null)

        assertEquals("2026-06-26", answer?.items?.first()?.batchDate)
        assertEquals("2026-06-26", answer?.items?.first()?.loadingDate)
        assertEquals("2026-06-27", answer?.items?.last()?.batchDate)
        assertEquals("2026-06-28", answer?.items?.last()?.loadingDate)
    }
}
