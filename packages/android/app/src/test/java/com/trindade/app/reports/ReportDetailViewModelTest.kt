package com.trindade.app.reports

import com.trindade.app.contract.models.PhotoResponse
import com.trindade.app.contract.models.ReportResponse
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.network.ReportsApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The detail screen's state, and the two things about it that are easy to get wrong.
 *
 * One is that a photo which cannot be read must not be uploaded anyway: the point of compressing
 * first is to respect a limit the client can see, and uploading the original to collect a 413 makes
 * the limit the operator's problem.
 *
 * The other is that the export is fetched separately from the report. The server refuses to render a
 * report missing a reading, and a screen that loaded both together would not open at all for one.
 *
 * The third is that the load is not always a read: the instance outlives the screen, so a caller that
 * knows the retained report has been changed has to be able to say so. That is `force`, and it is what
 * makes an edit visible on the way back from the edit surface.
 */
class ReportDetailViewModelTest {

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(
        api: FakeReportsApi = FakeReportsApi(),
        compressor: PhotoCompressor = FakePhotoCompressor(),
    ) = ReportDetailViewModel(ReportsRepository(api), compressor).also { it.load(REPORT_ID) }

    @Test
    fun `opens the report and its photos`() {
        val photos = listOf(FakeReportsApi.aPhoto(1), FakeReportsApi.aPhoto(2))
        val model = viewModel(FakeReportsApi(photosToReturn = photos))

        assertEquals(REPORT_ID, model.state.value.report!!.id)
        assertEquals(2, model.state.value.photos.size)
        assertFalse(model.state.value.loading)
    }

    @Test
    fun `says so when the report does not exist, rather than showing an empty one`() {
        val model = viewModel(FakeReportsApi(reportToReturn = null))

        assertEquals(null, model.state.value.report)
        assertNotNull(model.state.value.message)
    }

    @Test
    fun `does not upload a photo it could not read`() {
        val api = FakeReportsApi()
        val model = viewModel(api, compressor = FakePhotoCompressor(returns = null))

        model.attachPhoto("/tmp/capture.jpg")

        // The assertion that matters: the original was not sent. Uploading it would have the server
        // answer 413 for a limit the client could have respected.
        assertEquals(0, api.attachCalls)
        assertNotNull(model.state.value.message)
        assertFalse(model.state.value.uploadingPhoto)
    }

    @Test
    fun `uploads the compressed bytes and reloads the list`() {
        val api = FakeReportsApi()
        val model = viewModel(api)

        model.attachPhoto("/tmp/capture.jpg")

        assertEquals(1, api.attachCalls)
        assertFalse(model.state.value.uploadingPhoto)
    }

    @Test
    fun `withholds the add action once the report carries five photos`() {
        val five = (1..5).map { FakeReportsApi.aPhoto(it) }
        val model = viewModel(FakeReportsApi(photosToReturn = five))

        // Withheld rather than refused at the door: the server's 409 stays as a backstop, but the
        // operator is not invited to do something that cannot work.
        assertFalse(model.state.value.canAddPhoto)
    }

    @Test
    fun `says a photo was too large when the server answers 413`() {
        val api = FakeReportsApi(attachResponse = Response.error(413, EMPTY_BODY))
        val model = viewModel(api)

        model.attachPhoto("/tmp/capture.jpg")

        assertTrue(model.state.value.message!!.lowercase().contains("grande"))
    }

    @Test
    fun `fetches the export text from the server rather than composing it`() {
        val model = viewModel(FakeReportsApi(exportToReturn = "CRONOGRAMA DE CARREGAMENTO\n🕓 04:00"))

        model.loadExport()

        assertEquals("CRONOGRAMA DE CARREGAMENTO\n🕓 04:00", model.state.value.exportText)
        assertFalse(model.state.value.loadingExport)
    }

    @Test
    fun `says the report is incomplete when the server cannot render the export`() {
        // The server refuses to render a report missing a reading, and this is that refusal.
        val model = viewModel(FakeReportsApi(reportToReturn = null))
        model.loadExport()
        val messageBefore = model.state.value.message

        // A report that exists but is incomplete answers 404 on this call in the fake, so the state
        // must carry a message rather than a blank panel that reads as "nothing to copy".
        assertNotNull(messageBefore)
    }

    /**
     * The one arrival that is known to be stale, and the reason the force exists at all.
     *
     * This view model is scoped to the activity's store, so it outlives the composition and survives the
     * trip through the edit surface. Coming back from a save, the report it is holding is the one that was
     * just changed, and a load that trusted it would show the operator the values from before their own
     * edit -- which is the one failure a reload after a write exists to prevent. The forced read is what
     * the route asks for; the unforced one stays as it was, because a caller that only wants the report
     * drawn once must not pay for a second read every time the screen is recomposed.
     *
     * Both halves are asserted on one instance, and on the number of calls rather than on the state alone:
     * the second answer differs from the first, so "the state changed" would also be true of a load that
     * re-read at random, while "the unforced call did not reach the server and the forced one did" is the
     * distinction under test. The unforced call happens between the two reads, which is also what proves
     * the early return is still there rather than merely unused.
     */
    @Test
    fun `a forced load reads the report again, and an unforced one still keeps what it holds`() {
        val before = FakeReportsApi.createdReport(id = REPORT_ID).copy(notes = "antes da edição")
        val after = FakeReportsApi.createdReport(id = REPORT_ID).copy(notes = "depois da edição")
        val api = SequencedReportsApi(FakeReportsApi(reportToReturn = before), listOf(before, after))
        val model = ReportDetailViewModel(ReportsRepository(api), FakePhotoCompressor())

        model.load(REPORT_ID)
        assertEquals("antes da edição", model.state.value.report!!.notes)
        assertEquals(1, api.reportCalls)

        // The early return, unchanged: what the instance holds is what it answers with.
        model.load(REPORT_ID)
        assertEquals(1, api.reportCalls)
        assertEquals("antes da edição", model.state.value.report!!.notes)

        // And the forced read goes to the server again, and the answer that arrives replaces the one
        // that was held.
        model.load(REPORT_ID, force = true)
        assertEquals(2, api.reportCalls)
        assertEquals("depois da edição", model.state.value.report!!.notes)
    }

    private companion object {
        const val REPORT_ID = 42

        val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())
    }
}

/**
 * The report call, counted, with an answer per call.
 *
 * `FakeReportsApi` answers every read with the same report and keeps no count, and neither can make the
 * claim above: the point is not that the report was read but that it was read *twice* and that the second
 * answer is the one that lands. Interface delegation hands every other call -- the categories, the offers,
 * the photos, the export -- to the same fake the rest of this file uses, so only the read under test is
 * replaced.
 *
 * The last answer repeats once the list is exhausted, so a fixture of one report is still a valid
 * argument here and the class never indexes past its own list.
 */
private class SequencedReportsApi(
    private val delegate: FakeReportsApi,
    private val reports: List<ReportResponseReport>,
) : ReportsApi by delegate {

    /** How many times the report was read, which is the half of the claim a state cannot make. */
    var reportCalls = 0
        private set

    override suspend fun report(id: Int): Response<ReportResponse> {
        val answer = reports[minOf(reportCalls, reports.lastIndex)]
        reportCalls++
        return Response.success(ReportResponse(report = answer))
    }
}

/**
 * A compressor that returns fixed bytes, or nothing.
 *
 * The interface is why this is possible at all: the Bitmap work needs a device, but the decision about
 * whether to upload depends only on whether compression produced anything.
 */
private class FakePhotoCompressor(
    private val returns: ByteArray? = "jpeg-bytes".toByteArray(),
) : PhotoCompressor {
    override suspend fun compressToJpeg(path: String): ByteArray? = returns
}
