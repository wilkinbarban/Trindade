package com.trindade.app.auth

import com.trindade.app.contract.models.RegisterRequest
import com.trindade.app.di.NetworkModule
import com.trindade.app.network.AuthApi
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Validates wire payload serialization for [AuthApi.register] over real Retrofit and MockWebServer.
 *
 * Verifies that the request payload strictly sends only username, display_name and password
 * without role or status client fields, and parses neutral 200 responses as well as 400, 409,
 * and 429 rate-limited responses with Retry-After headers.
 */
class RegisterApiPayloadTest {

    private val appJson: Json = NetworkModule.provideJson()

    @Test(timeout = 5000L)
    fun `register endpoint sends RegisterRequest on wire without role or status fields`() {
        val server = MockWebServer()
        server.start()
        try {
            val neutralMessage =
                "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"message":"$neutralMessage"}"""),
            )

            val request = RegisterRequest(
                username = "operador1",
                displayName = "Operador Um",
                password = "secretpassword8",
            )

            val response = runBlocking { wireApi(server).register(request) }

            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/auth/register", sent.path)

            val body = sent.body.readUtf8()
            assertTrue("Expected username in wire body: $body", body.contains("\"username\":\"operador1\""))
            assertTrue("Expected display_name in wire body: $body", body.contains("\"display_name\":\"Operador Um\""))
            assertTrue("Expected password in wire body: $body", body.contains("\"password\":\"secretpassword8\""))

            // Invariant: no client role or active status authority may be transmitted
            assertFalse("Wire body must not contain role: $body", body.contains("\"role\""))
            assertFalse("Wire body must not contain role_id: $body", body.contains("\"role_id\""))
            assertFalse("Wire body must not contain is_active: $body", body.contains("\"is_active\""))

            assertTrue(response.isSuccessful)
            assertEquals(200, response.code())
            assertEquals(neutralMessage, response.body()?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `register handles duplicate username returning neutral 200 response`() {
        val server = MockWebServer()
        server.start()
        try {
            val neutralMessage =
                "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"message":"$neutralMessage"}"""),
            )

            val request = RegisterRequest(
                username = "duplicate_user",
                displayName = "Duplicate User",
                password = "secretpassword8",
            )

            val response = runBlocking { wireApi(server).register(request) }
            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)
            assertEquals("/api/auth/register", sent.path)

            assertTrue(response.isSuccessful)
            assertEquals(200, response.code())
            assertEquals(neutralMessage, response.body()?.message)
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `register handles 400 validation error on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(400)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"Invalid input","details":{"fieldErrors":{"password":["String must contain at least 8 character(s)"]}}}"""),
            )

            val request = RegisterRequest(
                username = "short",
                displayName = "Short",
                password = "123",
            )

            val response = runBlocking { wireApi(server).register(request) }
            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)

            assertFalse(response.isSuccessful)
            assertEquals(400, response.code())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `register handles 409 conflict bootstrap required on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(409)
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"error":"A configuração inicial do administrador é necessária antes de registrar usuários."}"""),
            )

            val request = RegisterRequest(
                username = "first_user",
                displayName = "First User",
                password = "secretpassword8",
            )

            val response = runBlocking { wireApi(server).register(request) }
            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)

            assertFalse(response.isSuccessful)
            assertEquals(409, response.code())
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 5000L)
    fun `register handles 429 rate limit with Retry-After header on wire`() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse()
                    .setResponseCode(429)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("Retry-After", "60")
                    .setBody("""{"error":"Limite de solicitações excedido. Tente novamente mais tarde."}"""),
            )

            val request = RegisterRequest(
                username = "flooder",
                displayName = "Flooder",
                password = "secretpassword8",
            )

            val response = runBlocking { wireApi(server).register(request) }
            val sent = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)) { "Timed out waiting for request" }
            assertEquals("POST", sent.method)

            assertFalse(response.isSuccessful)
            assertEquals(429, response.code())
            assertEquals("60", response.headers()["Retry-After"])
        } finally {
            server.shutdown()
        }
    }

    private fun wireApi(server: MockWebServer): AuthApi =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .addConverterFactory(appJson.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(AuthApi::class.java)
}
