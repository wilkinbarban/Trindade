package com.trindade.app.massas

import com.trindade.app.network.MassasApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.MediaType.Companion.toMediaType

/**
 * The refusal the repository hands to the view model.
 *
 * A status code alone is not a reason: the screen can only explain what the backend meant if the code and
 * the sentence survive the trip from the response body into `MassasWriteResult.Refused`.
 */
class MassasRepositoryRefusalTest {
    private lateinit var server: MockWebServer
    private lateinit var repository: MassasRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        repository = MassasRepository(retrofit.create(MassasApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun refuses(status: Int, body: String) {
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
    }

    @Test
    fun aRefusalKeepsTheCodeAndTheSentenceTheBackendSent() = runBlocking {
        refuses(409, """{"error":"O histórico de massas é somente leitura: dias passados não podem ser alterados","code":"MASSAS_HISTORY_FROZEN"}""")

        val outcome = repository.createLaunch("2020-01-01", listOf(com.trindade.app.network.CreateMassasLaunchItem("1045", 1)))

        val refused = outcome as MassasWriteResult.Refused
        assertEquals(409, refused.statusCode)
        assertEquals("MASSAS_HISTORY_FROZEN", refused.errorCode)
        assertEquals("O histórico de massas é somente leitura: dias passados não podem ser alterados", refused.message)
    }

    @Test
    fun aRefusalWithoutACodeStillKeepsTheSentence() = runBlocking {
        refuses(403, """{"error":"Este lançamento pertence a outro operário"}""")

        val outcome = repository.updateLaunch(id = 7, boxes = 2)

        val refused = outcome as MassasWriteResult.Refused
        assertEquals(403, refused.statusCode)
        assertEquals(null, refused.errorCode)
        assertEquals("Este lançamento pertence a outro operário", refused.message)
    }

    @Test
    fun aRefusalWithAnUnreadableBodyKeepsTheStatusCodeOnly() = runBlocking {
        refuses(500, "<html>error</html>")

        val outcome = repository.deleteLaunch(id = 7)

        val refused = outcome as MassasWriteResult.Refused
        assertEquals(500, refused.statusCode)
        assertEquals(null, refused.errorCode)
        assertEquals(null, refused.message)
    }
}
