package com.trindade.app.update

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class UpdateRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: UpdateRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        repository = UpdateRepository(retrofit.create(GitHubReleaseApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `deserializes GitHubRelease with assets array and fields`() = runBlocking {
        val json = """
            {
                "tag_name": "v0.5.0",
                "name": "Release v0.5.0",
                "published_at": "2026-10-18T10:00:00Z",
                "assets": [
                    {
                        "name": "trindade-0.5.0.apk",
                        "browser_download_url": "https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk",
                        "updated_at": "2026-10-18T10:30:00Z",
                        "size": 10528616
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(json),
        )

        val release = repository.latestRelease()
        assertNotNull(release)
        assertEquals("v0.5.0", release?.tagName)
        assertEquals("Release v0.5.0", release?.name)
        assertEquals("2026-10-18T10:00:00Z", release?.publishedAt)
        assertEquals(1, release?.assets?.size)

        val asset = release?.assets?.first()
        assertEquals("trindade-0.5.0.apk", asset?.name)
        assertEquals(
            "https://github.com/wilkinbarban/Trindade/releases/download/v0.5.0/trindade-0.5.0.apk",
            asset?.browserDownloadUrl,
        )
        assertEquals("2026-10-18T10:30:00Z", asset?.updatedAt)
        assertEquals(10528616L, asset?.size)
    }
}
