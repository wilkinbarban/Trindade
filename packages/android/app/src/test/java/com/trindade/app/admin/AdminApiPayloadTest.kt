package com.trindade.app.admin

import com.trindade.app.contract.models.UpdateAdminTaskRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AdminApi.updateTask] over real Retrofit and MockWebServer.
 *
 * Asserts that [UpdateAdminTaskRequest.isActive] is serialized as numeric JSON 0 or 1 on the wire
 * rather than as a string enum value `"0"` or `"1"`.
 */
class AdminApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test
    fun `updateTask with active status sends numeric is_active 1 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(taskAnswer(taskId = TASK_ID, isActive = 1)))

            runBlocking {
                wireApi(server).updateTask(
                    id = TASK_ID,
                    body = UpdateAdminTaskRequest(isActive = UpdateAdminTaskRequest.IsActive._1),
                )
            }

            val sent = server.takeRequest()

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/tasks/$TASK_ID", sent.path)
            assertEquals("""{"is_active":1}""", sent.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `updateTask with inactive status sends numeric is_active 0 on the wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody(taskAnswer(taskId = TASK_ID, isActive = 0)))

            runBlocking {
                wireApi(server).updateTask(
                    id = TASK_ID,
                    body = UpdateAdminTaskRequest(isActive = UpdateAdminTaskRequest.IsActive._0),
                )
            }

            val sent = server.takeRequest()

            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/tasks/$TASK_ID", sent.path)
            assertEquals("""{"is_active":0}""", sent.body.readUtf8())
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

    private fun taskAnswer(taskId: Int, isActive: Int): String =
        """{"task":{"id":$taskId,"category_id":1,"name_pt":"Higienização","name_es":"Higienización","temperature_readings":1,"is_active":$isActive,"created_by_user_id":null,"created_at":"2026-01-01T00:00:00Z","task_type":"check"}}"""

    private companion object {
        const val TASK_ID = 42
    }
}
