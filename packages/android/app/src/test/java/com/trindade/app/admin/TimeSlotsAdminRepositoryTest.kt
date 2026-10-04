package com.trindade.app.admin

import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class TimeSlotsAdminRepositoryTest {

    @Test
    fun `reads time slots and returns success result`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"timeSlots":["08:00","10:00"]}"""))
            val repository = repository(server)

            val result = repository.fetchTimeSlots()
            assertTrue(result is TimeSlotsReadResult.Success)
            assertEquals(listOf("08:00", "10:00"), (result as TimeSlotsReadResult.Success).timeSlots)

            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/api/admin/time-slots", request.path)
        }
    }

    @Test
    fun `fetchTimeSlots distinguishes 403 forbidden vs 500 error`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)

            val result403 = repository.fetchTimeSlots()
            assertTrue(result403 is TimeSlotsReadResult.Refused)
            assertEquals(403, (result403 as TimeSlotsReadResult.Refused).statusCode)

            val result500 = repository.fetchTimeSlots()
            assertTrue(result500 is TimeSlotsReadResult.Refused)
            assertEquals(500, (result500 as TimeSlotsReadResult.Refused).statusCode)
        }
    }

    @Test
    fun `fetchTimeSlots returns unreachable on disconnect`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val repository = repository(server)

            val result = repository.fetchTimeSlots()
            assertEquals(TimeSlotsReadResult.Unreachable, result)
            assertNull(repository.timeSlots())
        }
    }

    @Test
    fun `updateTimeSlots sends typed put request and parses response`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"timeSlots":["08:00","10:00","12:00"]}"""))
            val repository = repository(server)

            val result = repository.updateTimeSlots(listOf("08:00", "10:00", "12:00"))
            assertTrue(result is TimeSlotsAdminWriteResult.Saved)
            assertEquals(listOf("08:00", "10:00", "12:00"), (result as TimeSlotsAdminWriteResult.Saved).timeSlots)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/api/admin/time-slots", request.path)
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            val slots = body["time_slots"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("08:00", "10:00", "12:00"), slots)
        }
    }

    @Test
    fun `updateTimeSlots handles 403 refused and network disconnect`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val repository = repository(server)

            val refusedResult = repository.updateTimeSlots(listOf("08:00"))
            assertTrue(refusedResult is TimeSlotsAdminWriteResult.Refused)
            assertEquals(403, (refusedResult as TimeSlotsAdminWriteResult.Refused).statusCode)

            val unreachableResult = repository.updateTimeSlots(listOf("08:00"))
            assertEquals(TimeSlotsAdminWriteResult.Unreachable, unreachableResult)
        }
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .callTimeout(2, TimeUnit.SECONDS)
        .build()

    private fun repository(server: MockWebServer) = TimeSlotsAdminRepository(
        Retrofit.Builder().baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(jsonFormat.asConverterFactory("application/json".toMediaType()))
            .build().create(AdminApi::class.java),
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")
}
