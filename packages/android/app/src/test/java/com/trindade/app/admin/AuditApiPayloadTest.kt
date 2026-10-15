package com.trindade.app.admin

import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization and query parameters for [AdminApi.audit] over real Retrofit and MockWebServer.
 *
 * Asserts:
 * - GET /api/admin/audit query parameters match OpenAPI specification on the wire.
 * - Null query parameters are omitted by Retrofit.
 * - [com.trindade.app.contract.models.AuditResponse] and [com.trindade.app.contract.models.AuditResponseLogsInner] deserialization.
 * - Nullable fields (user_id, display_name, entity_id, ip_address, details) deserialize properly.
 * - HTTP 403 role refusal and HTTP 400 validation error responses are handled properly.
 */
class AuditApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    private fun wireApi(server: MockWebServer): AdminApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AdminApi::class.java)

    @Test
    fun `audit with default parameters sends GET to admin audit path without query string`() {
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

            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/audit", sent.path)
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

    @Test
    fun `audit with all query parameters encodes page, limit, action, entityType, and userId in URL`() {
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

            val sent = server.takeRequest()
            assertEquals("GET", sent.method)
            // Assert that sent URL path contains all expected query parameters
            val path = sent.path ?: ""
            assertTrue("Path must start with /api/admin/audit", path.startsWith("/api/admin/audit?"))
            assertTrue("Path must contain page=2", path.contains("page=2"))
            assertTrue("Path must contain limit=10", path.contains("limit=10"))
            assertTrue("Path must contain action=login", path.contains("action=login"))
            assertTrue("Path must contain entityType=auth", path.contains("entityType=auth"))
            assertTrue("Path must contain userId=5", path.contains("userId=5"))

            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(1, body!!.logs.size)
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

    @Test
    fun `audit handles nullable fields for deleted user or null metadata`() {
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

            assertTrue(response.isSuccessful)
            val log = response.body()!!.logs.first()
            assertEquals(102, log.id)
            assertNull(log.userId)
            assertNull(log.displayName)
            assertEquals("delete", log.action)
            assertEquals("user", log.entityType)
            assertNull(log.entityId)
            assertNull(log.ipAddress)
            assertNull(log.details)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `audit surfaces 403 role refusal when caller lacks administrator role`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(403)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"Administrador only"}"""),
            )

            val response = runBlocking { wireApi(server).audit() }

            assertFalse(response.isSuccessful)
            assertEquals(403, response.code())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `audit surfaces 400 bad request on invalid query parameters`() {
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

            assertFalse(response.isSuccessful)
            assertEquals(400, response.code())
        } finally {
            server.shutdown()
        }
    }
}
