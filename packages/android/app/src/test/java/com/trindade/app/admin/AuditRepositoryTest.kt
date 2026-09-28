package com.trindade.app.admin

import com.trindade.app.contract.models.AuditResponse
import com.trindade.app.network.AuditApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

class AuditRepositoryTest {

    private val jsonFormat = Json { ignoreUnknownKeys = true }

    private fun wireRepository(server: MockWebServer): AuditRepository {
        val api = Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(jsonFormat.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuditApi::class.java)
        return AuditRepository(api)
    }

    private fun jsonResponse(body: String, code: Int = 200) = MockResponse()
        .setResponseCode(code)
        .setBody(body)
        .addHeader("Content-Type", "application/json")

    @Test
    fun `serializes GET request to api admin audit with default page and fixed limit`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(SAMPLE_RESPONSE))
            val repository = wireRepository(server)

            val result = repository.audit(page = 1)

            assertNotNull(result)
            assertEquals(1, result?.total)
            assertEquals(1, result?.page)
            assertEquals(20, result?.limit)
            assertEquals(1, result?.totalPages)
            assertEquals(1, result?.logs?.size)

            val log = result?.logs?.first()
            assertEquals(101, log?.id)
            assertEquals(1, log?.userId)
            assertEquals("Administrador", log?.displayName)
            assertEquals("login", log?.action)
            assertEquals("auth", log?.entityType)
            assertNull(log?.entityId)
            assertEquals("127.0.0.1", log?.ipAddress)
            assertEquals("""{"agent":"test"}""", log?.details)
            assertEquals("2026-09-27T10:00:00Z", log?.createdAt)

            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertEquals("/api/admin/audit?page=1&limit=20", recordedRequest.path)
        }
    }

    @Test
    fun `serializes optional action entityType and userId query filters`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(SAMPLE_RESPONSE))
            val repository = wireRepository(server)

            val result = repository.audit(
                page = 2,
                action = "login",
                entityType = "auth",
                userId = 42,
            )

            assertNotNull(result)
            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertEquals("/api/admin/audit?page=2&limit=20&action=login&entityType=auth&userId=42", recordedRequest.path)
        }
    }

    @Test
    fun `omits blank or whitespace-only action and entityType filters from query`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(jsonResponse(SAMPLE_RESPONSE))
            val repository = wireRepository(server)

            val result = repository.audit(
                page = 1,
                action = "   ",
                entityType = "",
                userId = null,
            )

            assertNotNull(result)
            val recordedRequest = server.takeRequest()
            assertEquals("GET", recordedRequest.method)
            assertEquals("/api/admin/audit?page=1&limit=20", recordedRequest.path)
        }
    }

    @Test
    fun `fails closed and returns null on non-positive page without network request`() = runBlocking {
        MockWebServer().use { server ->
            val repository = wireRepository(server)

            assertNull(repository.audit(page = 0))
            assertNull(repository.audit(page = -1))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `fails closed and returns null on non-positive userId without network request`() = runBlocking {
        MockWebServer().use { server ->
            val repository = wireRepository(server)

            assertNull(repository.audit(page = 1, userId = 0))
            assertNull(repository.audit(page = 1, userId = -1))
            assertEquals(0, server.requestCount)
        }
    }

    @Test
    fun `fails closed and returns null on non-2xx HTTP responses`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setResponseCode(403))
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(500))
            val repository = wireRepository(server)

            assertNull(repository.audit(page = 1))
            assertNull(repository.audit(page = 1))
            assertNull(repository.audit(page = 1))
            assertNull(repository.audit(page = 1))
        }
    }

    @Test
    fun `fails closed and returns null on 204 or null response body`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val repository = wireRepository(server)

            assertNull(repository.audit(page = 1))
        }
    }

    @Test
    fun `fails closed and returns null on network disconnect`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            val repository = wireRepository(server)

            assertNull(repository.audit(page = 1))
        }
    }

    @Test
    fun `rethrows CancellationException without capturing it as null`() = runTest {
        val cancellation = CancellationException("operator left audit screen")
        val cancellingApi = object : AuditApi {
            override suspend fun audit(
                page: Int?,
                limit: Int?,
                action: String?,
                entityType: String?,
                userId: Int?,
            ): Response<AuditResponse> = throw cancellation
        }
        val repository = AuditRepository(cancellingApi)

        try {
            repository.audit(page = 1)
            fail("Expected CancellationException to be rethrown")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
    }

    private companion object {
        const val SAMPLE_RESPONSE = """{
            "logs": [
                {
                    "id": 101,
                    "user_id": 1,
                    "display_name": "Administrador",
                    "action": "login",
                    "entity_type": "auth",
                    "entity_id": null,
                    "ip_address": "127.0.0.1",
                    "details": "{\"agent\":\"test\"}",
                    "created_at": "2026-09-27T10:00:00Z"
                }
            ],
            "total": 1,
            "page": 1,
            "limit": 20,
            "totalPages": 1
        }"""
    }
}
