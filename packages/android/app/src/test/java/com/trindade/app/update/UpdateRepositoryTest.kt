package com.trindade.app.update

import com.trindade.app.network.BigDecimalSerializer
import com.trindade.app.network.SystemApi
import java.math.BigDecimal
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The update check reads this project's own API, and these tests are about that call: where it goes, what
 * it decodes, and what it answers when the API says no.
 *
 * The `Json` is the app's, contextual `BigDecimal` included, because the payload carries the APK size as a
 * JSON number and a lenient parser here would pass on bodies the device refuses.
 */
class UpdateRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: UpdateRepository
    private lateinit var retrofit: Retrofit

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json {
            ignoreUnknownKeys = true
            serializersModule = SerializersModule {
                contextual(BigDecimal::class, BigDecimalSerializer)
            }
        }
        retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        repository = UpdateRepository(retrofit.create(SystemApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `asks this project's api for the release it serves`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "version": "v0.7.0",
                        "apkUrl": "https://trindademasas.duckdns.org/api/app/download-apk",
                        "apkName": "trindade.apk",
                        "apkSize": 11149040,
                        "publishedAt": "2026-10-31T06:24:08.000Z",
                        "webUrl": "https://trindademasas.duckdns.org"
                    }
                    """.trimIndent(),
                ),
        )

        val release = repository.latestRelease()

        assertNotNull(release)
        assertEquals("v0.7.0", release?.version)
        assertEquals(
            "https://trindademasas.duckdns.org/api/app/download-apk",
            release?.apkUrl,
        )
        assertEquals(11149040L, release?.apkSize?.toLong())
        assertEquals("2026-10-31T06:24:08.000Z", release?.publishedAt)

        // The route, from the request that was really made: the whole point of the change is that this app
        // no longer asks a third party, and a base URL that still pointed at GitHub would answer this test
        // with the right body from the wrong host.
        assertEquals("/api/app/latest-release", server.takeRequest().path)
    }

    @Test
    fun `a release without a size still decodes`() = runBlocking {
        // The endpoint answers null while no APK is on disk, so the field is optional by contract and the
        // decoder has to accept it rather than fail the whole answer.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                        "version": "v0.7.0",
                        "apkUrl": "https://trindademasas.duckdns.org/api/app/download-apk",
                        "apkName": "trindade.apk",
                        "apkSize": null,
                        "publishedAt": null,
                        "webUrl": "https://trindademasas.duckdns.org"
                    }
                    """.trimIndent(),
                ),
        )

        val release = repository.latestRelease()

        assertNotNull(release)
        assertNull(release?.apkSize)
        assertNull(release?.publishedAt)
    }

    @Test
    fun `a refusal is no answer`() = runBlocking {
        // 502 is what the download route answers while the file is missing; the release route's own
        // refusals arrive the same way, and none of them is a statement about the operator.
        server.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"message":"APK download temporarily unavailable"}"""),
        )

        assertNull(repository.latestRelease())
    }

    @Test
    fun `an api that cannot be reached is no answer`() = runBlocking {
        server.shutdown()

        assertNull(repository.latestRelease())
    }
}
