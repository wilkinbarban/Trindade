package com.trindade.app.loading

import com.trindade.app.network.LoadingApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class LoadingRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: LoadingRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        repository = LoadingRepository(retrofit.create(LoadingApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `createDriver sends name, license_plate and phone on wire`() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"driver":{"id":77,"name":"Carlos Fletero","license_plate":"ABC-1234","phone":"+55 (11) 98888-7777","driver_type":"fletero"}}""")
        )

        val driver = repository.createDriver("Carlos Fletero", "ABC-1234", "+55 (11) 98888-7777")
        assertEquals(77, driver?.id)
        assertEquals("Carlos Fletero", driver?.name)
        assertEquals("ABC-1234", driver?.licensePlate)
        assertEquals("+55 (11) 98888-7777", driver?.phone)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/loading/drivers", recorded.path)
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        assertEquals("Carlos Fletero", body["name"]?.jsonPrimitive?.content)
        assertEquals("ABC-1234", body["license_plate"]?.jsonPrimitive?.content)
        assertEquals("+55 (11) 98888-7777", body["phone"]?.jsonPrimitive?.content)
    }
}
