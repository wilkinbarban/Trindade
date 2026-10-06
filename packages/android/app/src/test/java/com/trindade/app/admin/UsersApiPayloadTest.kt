package com.trindade.app.admin

import com.trindade.app.contract.models.AdminUserResponse
import com.trindade.app.contract.models.AdminUsersResponse
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AdminApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 * Validates wire transport and payload serialization for [AdminApi] user operations over real Retrofit, OkHttpClient,
 * and MockWebServer.
 *
 * Exercises wire transport and response parsing directly against [AdminApi] canonical signatures:
 * - GET /api/admin/users: parsing user list response.
 * - POST /api/admin/users: role_id 1 (Administrador) and role_id 2 (Trabalhador) transported with numeric primitives.
 * - PATCH /api/admin/users/{id}: numeric active 1 with role_id 1, and inactive 0 omitting untouched fields.
 * - DELETE /api/admin/users/{id}: handling 200 (Unit), 204 (null), and 403 (unsuccessful) without JSON body claims.
 *
 * Scope note: This suite verifies wire transport and serialization for [AdminApi] endpoints only; it does not test
 * DTO enum serialization or [com.trindade.app.admin.UsersRepository] mapping logic.
 * HTTP mock error tests verify client-side dispatch and error response handling; they do not prove server-side
 * role authorization enforcement or backend validation rules.
 */
class UsersApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()
    private val client = OkHttpClient()

    @After
    fun tearDown() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

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
            assertEquals(200, response.code())
            assertTrue(response.isSuccessful)
            val users = response.body()?.users
            assertNotNull(users)
            assertEquals(1, users?.size)
            val first = users?.first()
            assertEquals(1, first?.id)
            assertEquals("admin", first?.username)
            assertEquals("Admin", first?.displayName)
            assertEquals(1, first?.roleId)
            assertEquals("Administrador", first?.roleName)
            assertEquals(1, first?.isActive)
            assertEquals("2026-01-01T00:00:00Z", first?.createdAt)
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

            val payload = buildJsonObject {
                put("username", "novo_admin")
                put("display_name", "Novo Admin")
                put("password", "adminpassword")
                put("role_id", 1)
            }
            val response = runBlocking { wireApi(server).createUser(payload) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/users", sent.path)

            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("novo_admin", sentBody["username"]?.jsonPrimitive?.content)
            assertEquals("adminpassword", sentBody["password"]?.jsonPrimitive?.content)
            assertEquals("Novo Admin", sentBody["display_name"]?.jsonPrimitive?.content)
            val roleIdPrimitive = sentBody["role_id"]?.jsonPrimitive
            assertNotNull(roleIdPrimitive)
            assertFalse(roleIdPrimitive!!.isString)
            assertEquals("1", roleIdPrimitive.content)

            assertEquals(201, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(102, body?.user?.id)
            assertEquals("novo_admin", body?.user?.username)
            assertEquals("Novo Admin", body?.user?.displayName)
            assertEquals(1, body?.user?.roleId)
            assertEquals("Administrador", body?.user?.roleName)
            assertEquals(1, body?.user?.isActive)
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

            val payload = buildJsonObject {
                put("username", "novo_operador")
                put("display_name", "Novo Operador")
                put("password", "secretpassword")
                put("role_id", 2)
            }
            val response = runBlocking { wireApi(server).createUser(payload) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/admin/users", sent.path)

            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("novo_operador", sentBody["username"]?.jsonPrimitive?.content)
            assertEquals("secretpassword", sentBody["password"]?.jsonPrimitive?.content)
            assertEquals("Novo Operador", sentBody["display_name"]?.jsonPrimitive?.content)
            val roleIdPrimitive = sentBody["role_id"]?.jsonPrimitive
            assertNotNull(roleIdPrimitive)
            assertFalse(roleIdPrimitive!!.isString)
            assertEquals("2", roleIdPrimitive.content)

            assertEquals(201, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(101, body?.user?.id)
            assertEquals("novo_operador", body?.user?.username)
            assertEquals("Novo Operador", body?.user?.displayName)
            assertEquals(2, body?.user?.roleId)
            assertEquals("Trabalhador", body?.user?.roleName)
            assertEquals(1, body?.user?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `updateUser with active status sends numeric is_active 1 and numeric role_id 1 on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"user":{"id":$USER_ID,"username":"worker","display_name":"Worker Atualizado","role_id":1,"role_name":"Administrador","is_active":1,"created_at":"2026-01-01T00:00:00Z"}}"""),
            )

            val payload = buildJsonObject {
                put("display_name", "Worker Atualizado")
                put("role_id", 1)
                put("is_active", 1)
            }
            val response = runBlocking { wireApi(server).updateUser(USER_ID, payload) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/users/$USER_ID", sent.path)

            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals("Worker Atualizado", sentBody["display_name"]?.jsonPrimitive?.content)
            assertFalse("Unchanged password must be omitted from PATCH body", sentBody.containsKey("password"))
            assertFalse("Untouched username must be omitted from PATCH body", sentBody.containsKey("username"))

            val roleIdPrimitive = sentBody["role_id"]?.jsonPrimitive
            assertNotNull(roleIdPrimitive)
            assertFalse(roleIdPrimitive!!.isString)
            assertEquals("1", roleIdPrimitive.content)

            val isActivePrimitive = sentBody["is_active"]?.jsonPrimitive
            assertNotNull(isActivePrimitive)
            assertFalse(isActivePrimitive!!.isString)
            assertEquals("1", isActivePrimitive.content)

            assertEquals(200, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(USER_ID, body?.user?.id)
            assertEquals("worker", body?.user?.username)
            assertEquals("Worker Atualizado", body?.user?.displayName)
            assertEquals(1, body?.user?.roleId)
            assertEquals("Administrador", body?.user?.roleName)
            assertEquals(1, body?.user?.isActive)
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
                    .setBody("""{"user":{"id":$USER_ID,"username":"worker","display_name":"Worker","role_id":2,"role_name":"Trabalhador","is_active":0,"created_at":"2026-01-01T00:00:00Z"}}"""),
            )

            val payload = buildJsonObject {
                put("is_active", 0)
            }
            val response = runBlocking { wireApi(server).updateUser(USER_ID, payload) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("PATCH", sent.method)
            assertEquals("/api/admin/users/$USER_ID", sent.path)

            val sentBody = appJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertFalse("Untouched displayName must be omitted from PATCH body", sentBody.containsKey("display_name"))
            assertFalse("Unchanged password must be omitted from PATCH body", sentBody.containsKey("password"))
            assertFalse("Untouched username must be omitted from PATCH body", sentBody.containsKey("username"))
            assertFalse("Untouched roleId must be omitted from PATCH body", sentBody.containsKey("role_id"))

            val isActivePrimitive = sentBody["is_active"]?.jsonPrimitive
            assertNotNull(isActivePrimitive)
            assertFalse(isActivePrimitive!!.isString)
            assertEquals("0", isActivePrimitive.content)

            assertEquals(200, response.code())
            assertTrue(response.isSuccessful)
            val body = response.body()
            assertNotNull(body)
            assertEquals(USER_ID, body?.user?.id)
            assertEquals("worker", body?.user?.username)
            assertEquals(2, body?.user?.roleId)
            assertEquals(0, body?.user?.isActive)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteUser sends delete on wire and handles 200 success response as Unit`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"success":true}"""),
            )

            val response = runBlocking { wireApi(server).deleteUser(USER_ID) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/users/$USER_ID", sent.path)
            assertEquals(200, response.code())
            assertTrue(response.isSuccessful)
            assertEquals(Unit, response.body())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteUser sends delete on wire and handles 204 no content response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(204))

            val response = runBlocking { wireApi(server).deleteUser(USER_ID) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/users/$USER_ID", sent.path)
            assertEquals(204, response.code())
            assertTrue(response.isSuccessful)
            assertNull(response.body())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `deleteUser sends delete on wire and handles 403 refusal response`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(403))

            val response = runBlocking { wireApi(server).deleteUser(USER_ID) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("DELETE", sent.method)
            assertEquals("/api/admin/users/$USER_ID", sent.path)
            assertEquals(403, response.code())
            assertFalse(response.isSuccessful)
            assertNull(response.body())
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

    private companion object {
        const val USER_ID = 42
    }
}
