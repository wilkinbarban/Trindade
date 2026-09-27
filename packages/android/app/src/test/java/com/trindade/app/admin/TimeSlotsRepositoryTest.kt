package com.trindade.app.admin

import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.network.AdminApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class TimeSlotsRepositoryTest {
    @Test fun `reads time slots and sends typed update requests`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(json("""{"timeSlots":["04:00","04:30","05:00"]}"""))
            server.enqueue(json("""{"timeSlots":[]}"""))
            server.enqueue(json("""{"timeSlots":["08:00","09:00"]}"""))
            server.enqueue(json("""{"timeSlots":["10:00"]}"""))
            val repository = repository(server)

            assertEquals(listOf("04:00", "04:30", "05:00"), repository.timeSlots())
            assertEquals(emptyList<String>(), repository.timeSlots())
            assertEquals(
                TimeSlotWriteResult.Saved(listOf("08:00", "09:00")),
                repository.update(UpdateTimeSlotsRequest(timeSlots = listOf("08:00", "09:00"))),
            )
            assertEquals(
                TimeSlotWriteResult.Saved(listOf("10:00")),
                repository.update(listOf("10:00")),
            )

            val read1 = server.takeRequest()
            assertEquals("GET", read1.method)
            assertEquals("/api/admin/time-slots", read1.path)

            val read2 = server.takeRequest()
            assertEquals("GET", read2.method)
            assertEquals("/api/admin/time-slots", read2.path)

            val update1 = server.takeRequest()
            assertEquals("PUT", update1.method)
            assertEquals("/api/admin/time-slots", update1.path)
            val update1Body = Json.parseToJsonElement(update1.body.readUtf8()).jsonObject
            val update1Slots = update1Body["time_slots"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("08:00", "09:00"), update1Slots)

            val update2 = server.takeRequest()
            assertEquals("PUT", update2.method)
            assertEquals("/api/admin/time-slots", update2.path)
            val update2Body = Json.parseToJsonElement(update2.body.readUtf8()).jsonObject
            val update2Slots = update2Body["time_slots"]?.jsonArray?.map { it.jsonPrimitive.content }
            assertEquals(listOf("10:00"), update2Slots)
        }
    }

    @Test fun `failed reads stay null and writes preserve refusal status`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = repository(server)

            assertNull(repository.timeSlots())
            assertNull(repository.timeSlots())
            assertEquals(TimeSlotWriteResult.Refused(400), repository.update(listOf("invalid")))
            assertEquals(TimeSlotWriteResult.Refused(403), repository.update(listOf("08:00")))
            assertEquals(TimeSlotWriteResult.Refused(500), repository.update(listOf("08:00")))
        }
    }

    @Test fun `disconnected time slot writes are unreachable`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val repository = repository(server)

            assertNull(repository.timeSlots())
            assertEquals(TimeSlotWriteResult.Unreachable, repository.update(listOf("08:00")))
        }
    }

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private fun repository(server: MockWebServer) = TimeSlotsRepository(
        Retrofit.Builder().baseUrl(server.url("/"))
            .addConverterFactory(jsonFormat.asConverterFactory("application/json".toMediaType()))
            .build().create(AdminApi::class.java),
    )

    private fun json(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code).setBody(body).addHeader("Content-Type", "application/json")
}
