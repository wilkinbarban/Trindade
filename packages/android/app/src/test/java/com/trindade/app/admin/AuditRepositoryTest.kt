package com.trindade.app.admin

import com.trindade.app.contract.models.AuditResponse
import com.trindade.app.contract.models.AuditResponseLogsInner
import com.trindade.app.network.AdminApi
import java.io.IOException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class AuditRepositoryTest {

    private val sampleAuditResponse = AuditResponse(
        logs = listOf(
            AuditResponseLogsInner(
                id = 1,
                userId = 10,
                displayName = "Admin User",
                action = "login",
                entityType = "auth",
                entityId = 10,
                ipAddress = "127.0.0.1",
                details = "{\"method\":\"token\"}",
                createdAt = "2026-09-22T10:00:00.000Z",
            ),
        ),
        total = 1,
        page = 1,
        limit = 20,
        totalPages = 1,
    )

    @Test
    fun `queryAuditLogs returns Success with data on 200 response`() = runTest {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)

        val result = repo.queryAuditLogs(page = 1, limit = 20)

        assertTrue(result is AuditQueryResult.Success)
        val success = result as AuditQueryResult.Success
        assertEquals(1, success.page.page)
        assertEquals(20, success.page.limit)
        assertEquals(2, success.page.logs.size)
        assertEquals(1, fakeApi.auditCalls.size)
        assertEquals(1, fakeApi.auditCalls.first().page)
        assertEquals(20, fakeApi.auditCalls.first().limit)
    }

    @Test
    fun `queryAuditLogs returns Refused with 403 when API refuses caller role`() = runTest {
        val fakeApi = FakeAdminApi(
            auditResponse = Response.error(403, "{\"error\":\"Administrador only\"}".toResponseBody("application/json".toMediaType())),
        )
        val repo = AuditRepository(fakeApi)

        val result = repo.queryAuditLogs()

        assertTrue(result is AuditQueryResult.Refused)
        val refused = result as AuditQueryResult.Refused
        assertEquals(403, refused.statusCode)
    }

    @Test
    fun `queryAuditLogs returns Refused with 400 on bad query parameters`() = runTest {
        val fakeApi = FakeAdminApi(
            auditResponse = Response.error(400, "{\"error\":\"Invalid page\"}".toResponseBody("application/json".toMediaType())),
        )
        val repo = AuditRepository(fakeApi)

        val result = repo.queryAuditLogs(page = -5)

        assertTrue(result is AuditQueryResult.Refused)
        val refused = result as AuditQueryResult.Refused
        assertEquals(400, refused.statusCode)
    }

    @Test
    fun `queryAuditLogs returns Unreachable on network exception`() = runTest {
        val throwingApi = object : AdminApi by FakeAdminApi() {
            override suspend fun audit(
                page: Int?,
                limit: Int?,
                action: String?,
                entityType: String?,
                userId: Int?,
            ): Response<AuditResponse> {
                throw IOException("Network connection reset")
            }
        }
        val repo = AuditRepository(throwingApi)

        val result = repo.queryAuditLogs()

        assertTrue(result is AuditQueryResult.Unreachable)
    }

    @Test
    fun `queryAuditLogs forwards non-blank filters and passes null for blank filters`() = runTest {
        val fakeApi = FakeAdminApi()
        val repo = AuditRepository(fakeApi)

        repo.queryAuditLogs(
            page = 2,
            limit = 50,
            action = "update",
            entityType = "  ", // blank, should be passed as null
            userId = 7,
        )

        assertEquals(1, fakeApi.auditCalls.size)
        val call = fakeApi.auditCalls.first()
        assertEquals(2, call.page)
        assertEquals(50, call.limit)
        assertEquals("update", call.action)
        assertNull(call.entityType)
        assertEquals(7, call.userId)
    }

    @Test
    fun `queryAuditLogs returns Refused when response body is null`() = runTest {
        val fakeApi = FakeAdminApi(
            auditResponse = Response.success(null),
        )
        val repo = AuditRepository(fakeApi)

        val result = repo.queryAuditLogs()

        assertTrue(result is AuditQueryResult.Refused)
        assertEquals(200, (result as AuditQueryResult.Refused).statusCode)
    }
}
