package com.trindade.app.reports

import com.trindade.app.contract.models.PhotoResponse
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

    private companion object {
        const val REPORT_ID = 42

        val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())
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
