package com.trindade.app.reports

import com.trindade.app.contract.models.ReportHistoryResponse
import com.trindade.app.contract.models.ScheduleHistoryResponsePagination
import com.trindade.app.network.ReportsApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The report history read, and its pagination.
 *
 * Most of this class is about one trap: an absent filter and an empty filter are different requests to
 * this server and look identical at the call site. The server validates `date` against
 * `^\d{4}-\d{2}-\d{2}$`, so `date=` is a 400 while no `date` at all means the whole history. A
 * repository that tidied `null` into `""` would turn every unfiltered screen into an error nobody could
 * explain, and a repository that tidied `""` into `null` would silently answer a different question
 * than the one it was asked. Neither mistake is visible in a test that only checks the happy path, so
 * the assertions here are about what the client actually asked for.
 *
 * The last test is the only one that can prove the premise the other two rest on -- that a null
 * `@Query` never reaches the wire and an empty one arrives as the empty value. A fake records what the
 * repository passed; only a real socket shows what Retrofit did with it. It is written once here
 * rather than twice, because the loading history declares the same four parameters in the same shape
 * and the mechanism is Retrofit's, not either endpoint's.
 */
class HistoryRepositoryTest {

    private fun repository(api: FakeReportsApi = FakeReportsApi()) = ReportsRepository(api)

    @Test
    fun `asks for no filter at all when the caller set none`() = runTest {
        val api = FakeReportsApi()

        repository(api).history(date = null, month = null, page = null, pageSize = null)

        // All four null and not one empty string: the repository forwards the caller's absence as
        // absence, and a null page or pageSize asks the server for its own default of 1 and 30.
        assertEquals(
            ReportsHistoryQuery(date = null, month = null, page = null, pageSize = null),
            api.lastHistoryQuery,
        )
    }

    @Test
    fun `passes an empty filter through as empty rather than as no filter`() = runTest {
        val api = FakeReportsApi()

        repository(api).history(date = "", month = "", page = null, pageSize = null)

        // The other half of the trap. An empty date is not a missing date, and this layer has no
        // business deciding which the caller meant; it is a 400 either way, and the screen has to be
        // the place that stops sending it.
        assertEquals(
            ReportsHistoryQuery(date = "", month = "", page = null, pageSize = null),
            api.lastHistoryQuery,
        )
    }

    @Test
    fun `passes the page and the page size through as asked`() = runTest {
        val api = FakeReportsApi()

        // The server's maximum, sent unchanged. The repository has no opinion about the limits, and a
        // clamp here would be a second copy of the server's rule that nobody would remember to move.
        repository(api).history(date = "2026-06-18", month = null, page = 3, pageSize = 100)

        assertEquals(
            ReportsHistoryQuery(date = "2026-06-18", month = null, page = 3, pageSize = 100),
            api.lastHistoryQuery,
        )
    }

    @Test
    fun `answers null when the server refuses the read`() = runTest {
        // Null rather than an empty envelope, which is what this layer's failure convention is for: a
        // screen that could not tell "no reports that month" from "the server refused" would render an
        // empty history over a 400 and call it a fact.
        val answer = repository(FakeReportsApi(historyToReturn = null))
            .history(date = null, month = null, page = null, pageSize = null)

        assertNull(answer)
    }

    @Test
    fun `answers the envelope with its pagination rather than the items alone`() = runTest {
        val page = ReportHistoryResponse(
            items = listOf(FakeReportsApi.historyItem(id = 7, reportDate = "2026-04-30", isActive = false)),
            pagination = ScheduleHistoryResponsePagination(page = 2, pageSize = 30, total = 95, totalPages = 4),
        )

        val answer = repository(FakeReportsApi(historyToReturn = page))
            .history(date = null, month = "2026-04", page = 2, pageSize = 30)

        // The pagination is the half of the answer a bare list could not carry: `totalPages = 4` is
        // the only thing that tells the screen there is a page 3 worth offering, and `total` is the
        // only thing that can label it.
        assertEquals(95, answer?.pagination?.total)
        assertEquals(4, answer?.pagination?.totalPages)
        assertEquals(7, answer?.items?.single()?.id)
        assertEquals(false, answer?.items?.single()?.isActive)
    }

    @Test
    fun `leaves an absent permission flag absent instead of reading null as permission`() = runTest {
        // The contract marks the five lifecycle flags optional even though the server always emits
        // them, so a null arriving here is not something this client may interpret. It stays null: a
        // repository that defaulted it to true would hand the screen a permission the server never
        // granted, and the screen would draw an edit control that answers 403.
        val item = FakeReportsApi.historyItem().copy(canEdit = null, readOnly = null)
        val page = ReportHistoryResponse(
            items = listOf(item),
            pagination = ScheduleHistoryResponsePagination(page = 1, pageSize = 30, total = 1, totalPages = 1),
        )

        val answer = repository(FakeReportsApi(historyToReturn = page))
            .history(date = null, month = null, page = null, pageSize = null)

        assertNull(answer?.items?.single()?.canEdit)
        assertNull(answer?.items?.single()?.readOnly)
    }

    @Test
    fun `an absent filter stays off the wire and an empty one is sent as the empty value`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_HISTORY))
            server.enqueue(MockResponse().setResponseCode(200).setBody(EMPTY_HISTORY))
            val wire = wireApi(server)

            // `runBlocking` rather than `runTest`: this call crosses a real socket, and there is no
            // virtual time to advance that would make it finish any sooner.
            runBlocking {
                wire.history(date = null, month = null, page = null, pageSize = null)
                wire.history(date = "", month = "", page = null, pageSize = null)
            }

            // Asserted as the two paths, because the difference between them is the whole point: the
            // first carries no filter, the second carries two empty ones, and only the second is the
            // 400 that the server would send for a client that meant "no filter".
            assertEquals("/api/reports/history", server.takeRequest().path)
            assertEquals("/api/reports/history?date=&month=", server.takeRequest().path)
        } finally {
            server.shutdown()
        }
    }

    /** A real Retrofit over a real socket, configured the way the app configures its own. */
    private fun wireApi(server: MockWebServer): ReportsApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(wireJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(ReportsApi::class.java)

    private companion object {
        /** A complete answer that says nothing, so the assertions can be about the request alone. */
        const val EMPTY_HISTORY = """{"items":[],"pagination":{"page":1,"pageSize":30,"total":0,"totalPages":0}}"""
    }
}

/**
 * The same Json the app builds, so the wire test exercises the parser the app will use.
 *
 * At file level rather than inside the helper because the serialization plugin is right to warn about
 * a format built per call, and one process-wide instance is what `NetworkModule` provides too.
 */
private val wireJson = Json { ignoreUnknownKeys = true }
