package com.trindade.app.admin

import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AdminApi] time slot operations over real Retrofit and MockWebServer.
 */
class TimeSlotsAdminApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test(timeout = 5000L)
    fun `timeSlots reads configured slots on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"timeSlots":["04:00","05:00","06:00"]}"""),
            )

            val response = runBlocking {
                wireApi(server).timeSlots()
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/time-slots", sent.path)
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(listOf("04:00", "05:00", "06:00"), body?.timeSlots)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateTimeSlots sends time_slots key on wire matching schema`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"timeSlots":["08:00","10:00","12:00"]}"""),
            )

            val request = UpdateTimeSlotsRequest(timeSlots = listOf("08:00", "10:00", "12:00"))
            val response = runBlocking {
                wireApi(server).updateTimeSlots(request)
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("PUT", sent.method)
            assertEquals("/api/admin/time-slots", sent.path)
            assertEquals("""{"time_slots":["08:00","10:00","12:00"]}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            assertEquals(listOf("08:00", "10:00", "12:00"), response.body()?.timeSlots)
        } finally {
            server.shutdown()
        }
    }

    private fun wireApi(server: MockWebServer): AdminApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AdminApi::class.java)
}
