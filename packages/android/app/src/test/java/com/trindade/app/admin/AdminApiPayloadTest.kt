package com.trindade.app.admin

import com.trindade.app.contract.models.AdminTaskResponse
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AdminApi.updateTask] over real Retrofit and MockWebServer.
 *
 * Asserts that [JsonObject] with numeric `is_active` 0 or 1 is transported on the wire and that
 * [AdminTaskResponse] is deserialized from the response.
 */
class AdminApiPayloadTest {

    private companion object {
        /**
         * A guard against a hung request, not a latency budget.
         *
         * The claim these tests make is about what travels on the wire, so the only thing a tighter
         * timeout can produce is a red gate on a slow machine: five seconds was under what a round trip
         * takes when the whole suite runs in one container, and it failed that way twice before the
         * payload ever got a chance to be wrong. Thirty seconds still fails a real hang, and fails slowly
         * enough to be obviously a hang.
         */
        const val HANG_GUARD_MS = 30_000L
        const val TASK_ID = 42
    }

    private val appJson: Json = NetworkModule.provideJson()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    @Test(timeout = HANG_GUARD_MS)
    fun `updateTask transports numeric is_active 1 JsonObject on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(taskAnswer(taskId = TASK_ID, isActive = 1)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(1)))
            val response = runBlocking {
                wireApi(server).updateTask(
                    id = TASK_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(HANG_GUARD_MS, TimeUnit.MILLISECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/tasks/$TASK_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(TASK_ID, body?.task?.id)
            assertEquals(1, body?.task?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = HANG_GUARD_MS)
    fun `updateTask transports numeric is_active 0 JsonObject on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(taskAnswer(taskId = TASK_ID, isActive = 0)),
            )

            val payload = JsonObject(mapOf("is_active" to JsonPrimitive(0)))
            val response = runBlocking {
                wireApi(server).updateTask(
                    id = TASK_ID,
                    body = payload,
                )
            }

            val sent = checkNotNull(server.takeRequest(HANG_GUARD_MS, TimeUnit.MILLISECONDS)) { "Timed out waiting for request" }

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/tasks/$TASK_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(TASK_ID, body?.task?.id)
            assertEquals(0, body?.task?.isActive)
        } finally {
            server.shutdown()
        }
    }

    private fun wireApi(server: MockWebServer): AdminApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AdminApi::class.java)

    private fun taskAnswer(taskId: Int, isActive: Int): String =
        """{"task":{"id":$taskId,"category_id":1,"name_pt":"Higienização","name_es":"Higienización","temperature_readings":1,"is_active":$isActive,"created_by_user_id":null,"created_at":"2026-01-01T00:00:00Z","task_type":"check"}}"""

}
