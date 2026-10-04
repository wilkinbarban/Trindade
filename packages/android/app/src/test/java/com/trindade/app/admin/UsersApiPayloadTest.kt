package com.trindade.app.admin

import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
 * Validates wire payload serialization for [AdminApi] user operations over real Retrofit and MockWebServer.
 *
 * Ensures numeric enum serialization for `role_id` (1, 2) and `is_active` (0, 1) matching backend
 * Zod schemas and OpenAPI contract. All waits are bounded.
 */
class UsersApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test(timeout = 5000L)
    fun `users endpoint parses list response on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"users":[{"id":1,"username":"admin","display_name":"Admin","role_id":1,"role_name":"Administrador","is_active":1,"created_at":"2026-01-01T00:00:00Z"}]}"""),
            )

            val response = runBlocking { wireApi(server).users() }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("GET", sent.method)
            assertEquals("/api/admin/users", sent.path)
            assertTrue(response.isSuccessful)
            val users = response.body()?.users
            assertNotNull(users)
            assertEquals(1, users?.size)
            assertEquals("admin", users?.first()?.username)
            assertEquals(1, users?.first()?.roleId)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createUser with role Trabalhador sends numeric role_id 2 on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"user":{"id":101,"username":"novo_operador","display_name":"Novo Operador","role_id":2,"role_name":"Trabalhador","is_active":1,"created_at":"2026-01-01T00:00:00Z"}}"""),
            )

            val request = CreateAdminUserRequest(
                username = "novo_operador",
                displayName = "Novo Operador",
                password = "secretpassword",
                roleId = CreateAdminUserRequest.RoleId._2,
            )

            val response = runBlocking { wireApi(server).createUser(request) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/users", sent.path)
            val body = sent.body.readUtf8()
            assertTrue("Expected numeric role_id: 2 in wire body: $body", body.contains("\"role_id\":2"))
            assertTrue(response.isSuccessful)
            assertEquals(101, response.body()?.user?.id)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `createUser with role Administrador sends numeric role_id 1 on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(201)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"user":{"id":102,"username":"novo_admin","display_name":"Novo Admin","role_id":1,"role_name":"Administrador","is_active":1,"created_at":"2026-01-01T00:00:00Z"}}"""),
            )

            val request = CreateAdminUserRequest(
                username = "novo_admin",
                displayName = "Novo Admin",
                password = "adminpassword",
                roleId = CreateAdminUserRequest.RoleId._1,
            )

            val response = runBlocking { wireApi(server).createUser(request) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/users", sent.path)
            val body = sent.body.readUtf8()
            assertTrue("Expected numeric role_id: 1 in wire body: $body", body.contains("\"role_id\":1"))
            assertTrue(response.isSuccessful)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateUser with active status sends numeric is_active 1 and numeric role_id on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"user":{"id":42,"username":"worker","display_name":"Worker Atualizado","role_id":1,"role_name":"Administrador","is_active":1,"created_at":"2026-01-01T00:00:00Z"}}"""),
            )

            val request = UpdateAdminUserRequest(
                displayName = "Worker Atualizado",
                roleId = UpdateAdminUserRequest.RoleId._1,
                isActive = UpdateAdminUserRequest.IsActive._1,
            )

            val response = runBlocking { wireApi(server).updateUser(42, request) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/users/42", sent.path)
            val body = sent.body.readUtf8()
            assertTrue("Expected numeric is_active: 1 in wire body: $body", body.contains("\"is_active\":1"))
            assertTrue("Expected numeric role_id: 1 in wire body: $body", body.contains("\"role_id\":1"))
            assertTrue(response.isSuccessful)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateUser with inactive status sends numeric is_active 0 on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"user":{"id":42,"username":"worker","display_name":"Worker","role_id":2,"role_name":"Trabalhador","is_active":0,"created_at":"2026-01-01T00:00:00Z"}}"""),
            )

            val request = UpdateAdminUserRequest(
                isActive = UpdateAdminUserRequest.IsActive._0,
            )

            val response = runBlocking { wireApi(server).updateUser(42, request) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/users/42", sent.path)
            val body = sent.body.readUtf8()
            assertTrue("Expected numeric is_active: 0 in wire body: $body", body.contains("\"is_active\":0"))
            assertTrue(response.isSuccessful)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteUser sends DELETE request and parses boolean success on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":true}"""),
            )

            val response = runBlocking { wireApi(server).deleteUser(42) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/users/42", sent.path)
            assertTrue(response.isSuccessful)
            val successPrimitive = response.body()?.get("success") as? JsonPrimitive
            assertNotNull(successPrimitive)
            assertEquals(false, successPrimitive?.isString)
            assertEquals(true, successPrimitive?.booleanOrNull)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteUser parses boolean false or missing success`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":false}"""),
            )

            val response = runBlocking { wireApi(server).deleteUser(42) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/users/42", sent.path)
            assertTrue(response.isSuccessful)
            val successPrimitive = response.body()?.get("success") as? JsonPrimitive
            assertNotNull(successPrimitive)
            assertEquals(false, successPrimitive?.booleanOrNull)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteUser throws on malformed JSON payload`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(200).setBody("""not-json"""))

            try {
                runBlocking {
                    wireApi(server).deleteUser(42)
                }
                org.junit.Assert.fail("Expected exception on malformed json")
            } catch (e: Exception) {
                assertTrue(
                    e is kotlinx.serialization.SerializationException ||
                        e.cause is kotlinx.serialization.SerializationException,
                )
            }
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
