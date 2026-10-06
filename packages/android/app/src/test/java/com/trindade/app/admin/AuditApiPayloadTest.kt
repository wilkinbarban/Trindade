package com.trindade.app.admin

import com.trindade.app.contract.models.AuditResponse
import com.trindade.app.contract.models.AuditResponseLogsInner
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AuditApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization and query parameters for [AuditApi.audit] over real Retrofit, OkHttpClient,
 * and MockWebServer.
 *
 * Asserts:
 * - GET /api/admin/audit query parameters match interface definitions on the wire without assumed ordering.
 * - Default invocation sends no query string parameters and decodes default pagination fields.
 * - [AuditResponse] and [AuditResponseLogsInner] deserialization with all attributes including JSON string details.
 * - Nullable fields (user_id, display_name, entity_id, ip_address, details) deserialize properly when explicit null.
 * - Client surfaces HTTP 403 forbidden and HTTP 400 bad request error responses as unsuccessful (isSuccessful=false,
 *   code, body=null).
 *
 * Note: HTTP mock error tests verify client-side dispatch and error response handling; they do not prove server-side
 * role authorization enforcement or backend validation rules.
 */
class AuditApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun wireApi(server: MockWebServer): AuditApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(client)
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuditApi::class.java)

    @Test(timeout = 5000L)
    fun `audit with default parameters sends GET to admin audit path without query string and decodes pagination defaults`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"logs":[],"total":0,"page":1,"limit":20,"totalPages":1}"""),
            )

            val response = runBlocking { wireApi(server).audit() }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/audit", sent.path)
            val requestUrl = checkNotNull(sent.requestUrl) { "Request URL must not be null" }
            assertEquals("/api/admin/audit", requestUrl.encodedPath)
            assertEquals(0, requestUrl.querySize)

            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(0, body!!.logs.size)
            assertEquals(0, body.total)
            assertEquals(1, body.page)
            assertEquals(20, body.limit)
            assertEquals(1, body.totalPages)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `audit with all query parameters encodes page limit action entityType and userId and decodes full log attributes`() {
        val server = MockWebServer()
        server.start()
        try {
            val responseJson = """
                {
                  "logs": [
                    {
                      "id": 101,
                      "user_id": 5,
                      "display_name": "Operador",
                      "action": "login",
                      "entity_type": "auth",
                      "entity_id": 5,
                      "ip_address": "192.168.1.100",
                      "details": "{\"auth\":\"password\"}",
                      "created_at": "2026-09-22T10:00:00.000Z"
                    }
                  ],
                  "total": 1,
                  "page": 2,
                  "limit": 10,
                  "totalPages": 1
                }
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(responseJson),
            )

            val response = runBlocking {
                wireApi(server).audit(
                    page = 2,
                    limit = 10,
                    action = "login",
                    entityType = "auth",
                    userId = 5,
                )
            }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)

            val requestUrl = checkNotNull(sent.requestUrl) { "Request URL must not be null" }
            assertEquals("/api/admin/audit", requestUrl.encodedPath)
            assertEquals("2", requestUrl.queryParameter("page"))
            assertEquals("10", requestUrl.queryParameter("limit"))
            assertEquals("login", requestUrl.queryParameter("action"))
            assertEquals("auth", requestUrl.queryParameter("entityType"))
            assertEquals("5", requestUrl.queryParameter("userId"))
            assertEquals(5, requestUrl.querySize)
            assertEquals(setOf("page", "limit", "action", "entityType", "userId"), requestUrl.queryParameterNames)

            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body!!.logs.size)
            assertEquals(1, body.total)
            assertEquals(2, body.page)
            assertEquals(10, body.limit)
            assertEquals(1, body.totalPages)

            val log = body.logs.first()
            assertEquals(101, log.id)
            assertEquals(5, log.userId)
            assertEquals("Operador", log.displayName)
            assertEquals("login", log.action)
            assertEquals("auth", log.entityType)
            assertEquals(5, log.entityId)
            assertEquals("192.168.1.100", log.ipAddress)
            assertEquals("{\"auth\":\"password\"}", log.details)
            assertEquals("2026-09-22T10:00:00.000Z", log.createdAt)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `audit decodes log with explicit null optional fields`() {
        val server = MockWebServer()
        server.start()
        try {
            val responseJson = """
                {
                  "logs": [
                    {
                      "id": 102,
                      "user_id": null,
                      "display_name": null,
                      "action": "delete",
                      "entity_type": "user",
                      "entity_id": null,
                      "ip_address": null,
                      "details": null,
                      "created_at": "2026-09-22T11:00:00.000Z"
                    }
                  ],
                  "total": 1,
                  "page": 1,
                  "limit": 20,
                  "totalPages": 1
                }
            """.trimIndent()

            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody(responseJson),
            )

            val response = runBlocking { wireApi(server).audit() }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/audit", sent.path)

            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body!!.logs.size)

            val log = body.logs.first()
            assertEquals(102, log.id)
            assertNull(log.userId)
            assertNull(log.displayName)
            assertEquals("delete", log.action)
            assertEquals("user", log.entityType)
            assertNull(log.entityId)
            assertNull(log.ipAddress)
            assertNull(log.details)
            assertEquals("2026-09-22T11:00:00.000Z", log.createdAt)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `audit handles HTTP 403 forbidden error response`() {
        // HTTP mock error tests verify client-side response handling (isSuccessful=false, code=403, body=null)
        // and do NOT prove server-side role authorization enforcement.
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"Forbidden"}"""),
            )

            val response = runBlocking { wireApi(server).audit() }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/audit", sent.path)

            assertFalse(response.isSuccessful)
            assertEquals(403, response.code())
            assertNull(response.body())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `audit transmits invalid page query parameter and handles HTTP 400 bad request response`() {
        // Transmits page=-1 on the wire and verifies client-side HTTP 400 error response handling;
        // this test does NOT prove server-side parameter validation logic.
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"The query parameters failed validation"}"""),
            )

            val response = runBlocking { wireApi(server).audit(page = -1) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            val requestUrl = checkNotNull(sent.requestUrl) { "Request URL must not be null" }
            assertEquals("/api/admin/audit", requestUrl.encodedPath)
            assertEquals("-1", requestUrl.queryParameter("page"))

            assertFalse(response.isSuccessful)
            assertEquals(400, response.code())
            assertNull(response.body())
        } finally {
            server.shutdown()
        }
    }
}
