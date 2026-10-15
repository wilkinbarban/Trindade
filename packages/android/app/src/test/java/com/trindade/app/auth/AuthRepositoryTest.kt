package com.trindade.app.auth

import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LoginResponseUser
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.RegisterRequest
import com.trindade.app.contract.models.RegisterResponse
import com.trindade.app.contract.models.SetupRequest
import com.trindade.app.contract.models.SetupResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.logging.AppLogger
import com.trindade.app.network.AuthApi
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * Tests for [AuthRepository] covering worker enrollment (registration)
 * and authentication session invariants.
 */
class AuthRepositoryTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `register success returns neutral message and preserves no session or tokens`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val neutralMessage =
            "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."
        val api = TestAuthApi(
            registerResult = Response.success(RegisterResponse(message = neutralMessage)),
        )
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("novo_trabalhador", "Novo Trabalhador", "senha12345")

        assertEquals(RegisterResult.Success(neutralMessage), result)
        // Critical safety invariant: registration must never issue, store, or rotate tokens
        assertNull(repository.accessToken())
        assertNull(repository.sessionRole())
        assertFalse(repository.hasSession())
        assertEquals(0, repository.sessionGeneration)
        assertNull(tokenStore.accessToken())
        assertNull(tokenStore.refreshToken())
        assertNull(tokenStore.role())
    }

    @Test
    fun `register replaces the schema validator's English invalid input on 400 with the Portuguese fallback`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val errorBody = """{"error":"Invalid input"}""".toResponseBody("application/json".toMediaType())
        val api = TestAuthApi(
            registerResult = Response.error(400, errorBody),
        )
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("bad", "Bad", "123")

        assertTrue(result is RegisterResult.Rejected)
        val rejected = result as RegisterResult.Rejected
        assertEquals(400, rejected.statusCode)
        // The validator's own sentence is English and names no field, so it is the one refusal this
        // client does not repeat: the operator reads this client's Portuguese 400 sentence instead.
        assertEquals("Dados de cadastro inválidos. Verifique as informações e tente novamente.", rejected.message)
        assertNull(rejected.retryAfterSeconds)
        assertFalse(repository.hasSession())
    }

    @Test
    fun `register preserves a Portuguese 400 refusal message the backend wrote`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val portuguese = "O nome de usuário já está em uso."
        val errorBody = """{"error":"$portuguese"}""".toResponseBody("application/json".toMediaType())
        val api = TestAuthApi(
            registerResult = Response.error(400, errorBody),
        )
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("used", "Used", "secret12345")

        assertTrue(result is RegisterResult.Rejected)
        val rejected = result as RegisterResult.Rejected
        assertEquals(400, rejected.statusCode)
        assertEquals(portuguese, rejected.message)
    }

    @Test
    fun `register falls back to the Portuguese 400 sentence when the refusal envelope is unreadable`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val api = TestAuthApi(
            registerResult = Response.error(400, "".toResponseBody("application/json".toMediaType())),
        )
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("bad", "Bad", "123")

        assertTrue(result is RegisterResult.Rejected)
        val rejected = result as RegisterResult.Rejected
        assertEquals(400, rejected.statusCode)
        assertEquals("Dados de cadastro inválidos. Verifique as informações e tente novamente.", rejected.message)
    }

    @Test
    fun `register repeats the English invalid input on a status other than 400`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val errorBody = """{"error":"Invalid input"}""".toResponseBody("application/json".toMediaType())
        val api = TestAuthApi(
            registerResult = Response.error(409, errorBody),
        )
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("user", "User", "secret12345")

        assertTrue(result is RegisterResult.Rejected)
        val rejected = result as RegisterResult.Rejected
        assertEquals(409, rejected.statusCode)
        // The replacement is 400-only, so a body that arrived on any other status is repeated as it came.
        assertEquals("Invalid input", rejected.message)
    }

    @Test
    fun `register handles 409 bootstrap required rejection`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val errorBody =
            """{"error":"A configuração inicial do administrador é necessária antes de registrar usuários."}"""
                .toResponseBody("application/json".toMediaType())
        val api = TestAuthApi(
            registerResult = Response.error(409, errorBody),
        )
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("user", "User", "secret12345")

        assertTrue(result is RegisterResult.Rejected)
        val rejected = result as RegisterResult.Rejected
        assertEquals(409, rejected.statusCode)
        assertEquals(
            "A configuração inicial do administrador é necessária antes de registrar usuários.",
            rejected.message,
        )
        assertFalse(repository.hasSession())
    }

    @Test
    fun `register handles 429 rate limit with Retry-After header`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val errorBody =
            """{"error":"Limite de solicitações excedido. Tente novamente mais tarde."}"""
                .toResponseBody("application/json".toMediaType())
        val headers = Headers.Builder().add("Retry-After", "45").build()
        val rawResponse = okhttp3.Response.Builder()
            .code(429)
            .message("Too Many Requests")
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .headers(headers)
            .build()
        val api = TestAuthApi(
            registerResult = Response.error(errorBody, rawResponse),
        )
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("flooder", "Flooder", "secret12345")

        assertTrue(result is RegisterResult.Rejected)
        val rejected = result as RegisterResult.Rejected
        assertEquals(429, rejected.statusCode)
        assertEquals("Limite de solicitações excedido. Tente novamente mais tarde.", rejected.message)
        assertEquals(45, rejected.retryAfterSeconds)
        assertFalse(repository.hasSession())
    }

    @Test
    fun `register handles timeout transport failure`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val api = TestAuthApi(transportFailure = SocketTimeoutException("Read timed out"))
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("user", "User", "secret12345")

        assertEquals(RegisterResult.Unreachable(UnreachableCause.Timeout), result)
        assertFalse(repository.hasSession())
    }

    @Test
    fun `register handles no route transport failure`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val api = TestAuthApi(transportFailure = UnknownHostException("api.example.com"))
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("user", "User", "secret12345")

        assertEquals(RegisterResult.Unreachable(UnreachableCause.NoRoute), result)
        assertFalse(repository.hasSession())
    }

    @Test
    fun `register handles TLS transport failure`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val api = TestAuthApi(transportFailure = SSLException("Handshake failed"))
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("user", "User", "secret12345")

        assertEquals(RegisterResult.Unreachable(UnreachableCause.Tls), result)
        assertFalse(repository.hasSession())
    }

    @Test
    fun `register handles unreadable body transport failure`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val api = TestAuthApi(transportFailure = SerializationException("Corrupted JSON"))
        val repository = AuthRepository(api, tokenStore, json)

        val result = repository.register("user", "User", "secret12345")

        assertEquals(RegisterResult.Unreachable(UnreachableCause.UnreadableBody), result)
        assertFalse(repository.hasSession())
    }

    @Test
    fun `register handles unknown transport failure and logs warning`() = runBlocking {
        val tokenStore = FakeTokenStore()
        val exception = IOException("Disk error")
        val logger = TestLogger()
        val api = TestAuthApi(transportFailure = exception)
        val repository = AuthRepository(api, tokenStore, json, logger)

        val result = repository.register("user", "User", "secret12345")

        assertEquals(RegisterResult.Unreachable(UnreachableCause.Unknown), result)
        assertEquals(1, logger.warnLogs.size)
        val log = logger.warnLogs.single()
        assertEquals("AuthRepository", log.tag)
        assertTrue(log.message.contains("Register failed without a usable answer: Unknown"))
        assertEquals(exception, log.throwable)
        assertFalse(repository.hasSession())
    }

    private class TestAuthApi(
        private val registerResult: Response<RegisterResponse> = Response.success(
            RegisterResponse(message = "ok"),
        ),
        private val transportFailure: Throwable? = null,
    ) : AuthApi {
        override suspend fun register(body: RegisterRequest): Response<RegisterResponse> {
            transportFailure?.let { throw it }
            return registerResult
        }

        override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
        override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
        override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
        override suspend fun me(): Response<ProfileResponse> = error("unused")
        override suspend fun profile(): Response<ProfileResponse> = error("unused")
        override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
        override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
        override suspend fun setup(body: SetupRequest): Response<SetupResponse> = error("unused")
        override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
    }

    private class FakeTokenStore : TokenStore {
        private var access: String? = null
        private var refresh: String? = null
        private var role: String? = null

        override fun accessToken(): String? = access
        override fun refreshToken(): String? = refresh
        override fun role(): String? = role

        override fun save(accessToken: String, refreshToken: String) {
            access = accessToken
            refresh = refreshToken
        }

        override fun saveRole(role: String) {
            this.role = role
        }

        override fun clear() {
            access = null
            refresh = null
            role = null
        }
    }

    private class TestLogger : AppLogger {
        data class WarnLog(val tag: String, val message: String, val throwable: Throwable?)
        val warnLogs = mutableListOf<WarnLog>()

        override fun w(tag: String, message: String, throwable: Throwable?) {
            warnLogs.add(WarnLog(tag, message, throwable))
        }
    }
}
