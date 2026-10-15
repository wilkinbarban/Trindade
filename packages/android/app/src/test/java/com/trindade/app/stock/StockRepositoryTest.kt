package com.trindade.app.stock

import com.trindade.app.network.StockApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * The counting client, against a server that answers what each case needs.
 *
 * MockWebServer rather than a fake interface, because half of what this class does is read the shape of an
 * answer: a body, a refusal, a timeout. A fake would let me assert my own idea of the wire instead of the
 * wire itself, and it would not carry the generated models, which are half of what is being tested here.
 */
class StockRepositoryTest {

    private lateinit var server: MockWebServer
    private lateinit var repository: StockRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
        val retrofit = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
        repository = StockRepository(retrofit.create(StockApi::class.java))
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun answer(code: Int, body: String) {
        server.enqueue(
            MockResponse().setResponseCode(code).setBody(body).setHeader("content-type", "application/json")
        )
    }

    @Test
    fun `a day comes back with its products and its totals`() = runBlocking {
        answer(200, """{"date":"2026-10-15","products":[],"totalBoxes":0,"totalUnits":0}""")
        val day = repository.day("2026-10-15")
        assertEquals("2026-10-15", day?.date)
        assertEquals(0, day?.totalBoxes)
        assertEquals("/api/stock?date=2026-10-15", server.takeRequest().path)
    }

    @Test
    fun `a day the server refuses is nothing, and an unreachable server is nothing too`() = runBlocking {
        answer(401, """{"error":"unauthorized"}""")
        assertNull("un rechazo no es un dia vacio", repository.day("2026-10-15"))

        server.shutdown()
        assertNull(repository.day("2026-10-15"))
    }

    @Test
    fun `adding boxes sends the keys the schema asks for and reports what came back`() = runBlocking {
        answer(200, """{"code":"787","name":"CANUDO","category":"Terceirizados","unitsPerBox":30,"boxes":5,"units":150}""")
        val result = repository.addEntry("2026-10-15", "787", 5)
        assertTrue(result is StockRepository.WriteResult.Saved)
        assertEquals(5, (result as StockRepository.WriteResult.Saved).product.boxes)

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertEquals("/api/stock/add-entry", request.path)
        assertTrue("la fecha viaja", body.contains("\"date\":\"2026-10-15\""))
        assertTrue("el producto viaja", body.contains("\"product_code\":\"787\""))
        assertTrue("los bultos viajan", body.contains("\"boxes_added\":5"))
    }

    @Test
    fun `a refused write is a refusal and not an unreachable server`() = runBlocking {
        answer(400, """{"error":"boxes must be a positive whole number, got 0"}""")
        assertEquals(StockRepository.WriteResult.Refused, repository.addEntry("2026-10-15", "787", 0))
    }

    @Test
    fun `resetting one product sends only the day and the product`() = runBlocking {
        answer(200, """{"code":"787","name":"CANUDO","category":"Terceirizados","unitsPerBox":30,"boxes":0,"units":0}""")
        assertTrue(repository.resetProduct("2026-10-15", "787") is StockRepository.WriteResult.Saved)

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"product_code\":\"787\""))
        assertTrue("un reset no lleva bultos", !body.contains("boxes_added"))
    }

    @Test
    fun `the history lists the days that were counted`() = runBlocking {
        answer(200, """{"days":[{"date":"2026-10-15","products":2,"boxes":5,"units":210}]}""")
        val days = repository.history()
        assertEquals(1, days?.size)
        assertEquals("2026-10-15", days?.first()?.date)
        assertEquals(5, days?.first()?.boxes)
    }
}
