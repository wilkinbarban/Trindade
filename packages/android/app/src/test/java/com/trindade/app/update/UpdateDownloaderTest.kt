package com.trindade.app.update

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.Instant
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class UpdateDownloaderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var downloader: UpdateDownloader

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient()
        downloader = UpdateDownloader(client, context)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `downloadApk streams progress and emits Completed state with file`() {
        runBlocking {
            val payload = ByteArray(16384) { it.toByte() }
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", payload.size.toString())
                    .setBody(okio.Buffer().write(payload)),
            )

            val states = downloader.downloadApk(server.url("/trindade-0.5.0.apk").toString(), "test-download.apk").toList()

            assertTrue(states.isNotEmpty())
            val downloadingStates = states.filterIsInstance<DownloadState.Downloading>()
            assertTrue(downloadingStates.isNotEmpty())
            val lastProgress = downloadingStates.last()
            assertEquals(16384L, lastProgress.bytesDownloaded)
            assertEquals(16384L, lastProgress.totalBytes)
            assertEquals(100, lastProgress.percent)

            val completed = states.last() as DownloadState.Completed
            assertTrue(completed.file.exists())
            assertEquals(16384L, completed.file.length())
            completed.file.delete()
        }
    }

    @Test
    fun `downloadApk emits Error state on HTTP 404 response`() {
        runBlocking {
            server.enqueue(MockResponse().setResponseCode(404))

            val states = downloader.downloadApk(server.url("/missing.apk").toString(), "missing.apk").toList()

            val lastState = states.last()
            assertTrue(lastState is DownloadState.Error)
            val error = lastState as DownloadState.Error
            assertTrue(error.message.contains("404"))
        }
    }

    @Test
    fun `detects new release when tag is newer`() {
        val release = GitHubRelease(
            tagName = "v0.6.0",
            assets = listOf(
                GitHubAsset(
                    name = "trindade-0.6.0.apk",
                    browserDownloadUrl = "https://github.com/test/app.apk",
                    updatedAt = "2026-10-18T10:00:00Z",
                    size = 1000L,
                ),
            ),
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.0",
            currentBuildTimeMillis = Instant.parse("2026-10-18T09:00:00Z").toEpochMilli(),
        )

        assertEquals(UpdateType.NEW_RELEASE, availability.type)
        assertTrue(availability.isUpdateAvailable)
        assertNotNull(availability.apkAsset)
    }

    @Test
    fun `detects same release patch when tag matches and remote asset is newer than local build time`() {
        val release = GitHubRelease(
            tagName = "v0.5.0",
            assets = listOf(
                GitHubAsset(
                    name = "trindade-0.5.0.apk",
                    browserDownloadUrl = "https://github.com/test/app.apk",
                    updatedAt = "2026-10-18T12:00:00Z",
                    size = 1000L,
                ),
            ),
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.0",
            currentBuildTimeMillis = Instant.parse("2026-10-18T10:00:00Z").toEpochMilli(),
        )

        assertEquals(UpdateType.SAME_RELEASE_PATCH, availability.type)
        assertTrue(availability.isUpdateAvailable)
    }

    @Test
    fun `returns NONE when tag matches and remote asset is older or equal to local build time`() {
        val release = GitHubRelease(
            tagName = "v0.5.0",
            assets = listOf(
                GitHubAsset(
                    name = "trindade-0.5.0.apk",
                    browserDownloadUrl = "https://github.com/test/app.apk",
                    updatedAt = "2026-10-18T09:00:00Z",
                    size = 1000L,
                ),
            ),
        )

        val availability = UpdateDetector.checkUpdate(
            release = release,
            currentVersionName = "0.5.0",
            currentBuildTimeMillis = Instant.parse("2026-10-18T10:00:00Z").toEpochMilli(),
        )

        assertEquals(UpdateType.NONE, availability.type)
        assertFalse(availability.isUpdateAvailable)
    }
}
