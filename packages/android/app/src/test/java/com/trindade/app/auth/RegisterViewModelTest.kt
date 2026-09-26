package com.trindade.app.auth

import com.trindade.app.contract.models.*
import com.trindade.app.network.AuthApi
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import retrofit2.Response

class RegisterViewModelTest {
    @Before fun setUp() { Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private val api = TestApi()
    private val model by lazy { RegisterViewModel(AuthRepository(api, TestTokens(), Json)) }

    private fun fill(password: String = "senha12345") {
        model.onUsernameChange("  joao  ")
        model.onDisplayNameChange("  Joao Silva  ")
        model.onPasswordChange(password)
        model.onConfirmPasswordChange(password)
    }

    @Test fun `initial state and incomplete form cannot submit`() {
        assertEquals(RegisterViewModel.UiState(), model.state.value)
        model.submit()
        assertEquals(0, api.calls)
        model.onUsernameChange("joao")
        assertFalse(model.state.value.canSubmit)
    }

    @Test fun `field edits clear validation and reset clears the whole form`() {
        fill()
        model.onConfirmPasswordChange("different")
        model.submit()
        assertEquals(RegisterMessage.Validation(RegisterViewModel.PASSWORD_MISMATCH), model.state.value.message)
        model.onConfirmPasswordChange("senha12345")
        assertNull(model.state.value.message)
        assertTrue(model.state.value.canSubmit)
        model.reset()
        assertEquals(RegisterViewModel.UiState(), model.state.value)
    }

    @Test fun `rejects invalid fields before network request`() {
        fill("short")
        model.submit()
        assertEquals(RegisterMessage.Validation(RegisterViewModel.PASSWORD_TOO_SHORT), model.state.value.message)
        model.onPasswordChange("senha12345")
        model.onConfirmPasswordChange("different")
        model.submit()
        assertEquals(RegisterMessage.Validation(RegisterViewModel.PASSWORD_MISMATCH), model.state.value.message)
        model.onConfirmPasswordChange("senha12345")
        model.onUsernameChange("u".repeat(51))
        model.submit()
        assertEquals(RegisterMessage.Validation(RegisterViewModel.USERNAME_TOO_LONG), model.state.value.message)
        model.onUsernameChange("joao")
        model.onDisplayNameChange("d".repeat(101))
        model.submit()
        assertEquals(RegisterMessage.Validation(RegisterViewModel.DISPLAY_NAME_TOO_LONG), model.state.value.message)
        assertEquals(0, api.calls)
    }

    @Test fun `name limits use transmitted trimmed values`() {
        fill()
        model.onUsernameChange("  " + "u".repeat(50) + "  ")
        model.onDisplayNameChange("  " + "d".repeat(100) + "  ")
        model.submit()
        assertEquals(1, api.calls)
        assertEquals("u".repeat(50), api.request?.username)
        assertEquals("d".repeat(100), api.request?.displayName)
    }

    @Test fun `password length and UTF-8 byte limits reject before API`() {
        for ((password, expected) in listOf(
            "p".repeat(73) to RegisterViewModel.PASSWORD_TOO_LONG,
            "€".repeat(25) to RegisterViewModel.PASSWORD_TOO_MANY_BYTES,
        )) {
            model.reset()
            fill(password)
            model.submit()
            assertEquals(RegisterMessage.Validation(expected), model.state.value.message)
            assertEquals(0, api.calls)
        }
    }

    @Test fun `password at both exact limits reaches API`() {
        for (password in listOf("p".repeat(72), "€".repeat(24))) {
            model.reset()
            fill(password)
            model.submit()
            assertEquals(password, api.request?.password)
        }
        assertEquals(2, api.calls)
    }

    @Test fun `reset ignores completion of an in-flight registration`() {
        val pending = CompletableDeferred<Response<RegisterResponse>>()
        api.pending = pending
        fill()
        model.submit()
        assertTrue(model.state.value.submitting)
        assertEquals(1, api.calls)
        model.reset()
        assertEquals(RegisterViewModel.UiState(), model.state.value)
        pending.complete(Response.success(RegisterResponse(message = "late success")))
        assertEquals(RegisterViewModel.UiState(), model.state.value)
    }

    @Test fun `valid submission trims names and clears passwords on success`() {
        fill()
        assertTrue(model.state.value.canSubmit)
        model.submit()
        assertEquals(1, api.calls)
        assertEquals("joao", api.request?.username)
        assertEquals("Joao Silva", api.request?.displayName)
        assertEquals("senha12345", api.request?.password)
        assertEquals("received", model.state.value.successMessage)
        assertEquals("", model.state.value.password)
        assertEquals("", model.state.value.confirmPassword)
        assertFalse(model.state.value.canSubmit)
        model.submit()
        assertEquals(1, api.calls)
    }

    @Test fun `neutral conditional success clears passwords without issuing a session`() {
        api.response = Response.success(RegisterResponse(message = NEUTRAL_MESSAGE))
        fill()
        model.submit()
        assertEquals(1, api.calls)
        assertEquals(NEUTRAL_MESSAGE, model.state.value.successMessage)
        assertNull(model.state.value.message)
        assertEquals("", model.state.value.password)
        assertEquals("", model.state.value.confirmPassword)
        assertFalse(model.state.value.canSubmit)
    }

    @Test fun `400 default validation refusal shows Portuguese correction`() {
        api.response = Response.error(400, """{"error":"Invalid input"}""".toResponseBody("application/json".toMediaType()))
        fill()
        model.submit()
        assertEquals(1, api.calls)
        assertFalse(model.state.value.submitting)
        assertNull(model.state.value.successMessage)
        assertEquals(RegisterMessage.FromServer("Dados de cadastro inválidos. Verifique as informações e tente novamente."), model.state.value.message)
        assertEquals("senha12345", model.state.value.password)
    }

    @Test fun `409 bootstrap refusal preserves server words`() {
        val refusal = "A configuração inicial do administrador é necessária antes de registrar usuários."
        api.response = Response.error(409, """{"error":"$refusal"}""".toResponseBody("application/json".toMediaType()))
        fill()
        model.submit()
        assertEquals(1, api.calls)
        assertFalse(model.state.value.submitting)
        assertNull(model.state.value.successMessage)
        assertEquals(RegisterMessage.FromServer(refusal), model.state.value.message)
    }

    @Test fun `429 refusal exposes Retry-After seconds`() {
        val refusal = "Limite de solicitações excedido. Tente novamente mais tarde."
        api.rejectWithHeaders(429, refusal, Headers.Builder().add("Retry-After", "90").build())
        fill()
        model.submit()
        assertEquals(1, api.calls)
        assertFalse(model.state.value.submitting)
        assertNull(model.state.value.successMessage)
        assertEquals(RegisterMessage.RateLimited(refusal, 90), model.state.value.message)
    }

    @Test fun `429 without usable Retry-After retains rate limit without duration`() {
        api.rejectWithHeaders(429, "Tente novamente mais tarde.", Headers.Builder().add("Retry-After", "tomorrow").build())
        fill()
        model.submit()
        assertEquals(RegisterMessage.RateLimited("Tente novamente mais tarde.", null), model.state.value.message)
    }

    @Test fun `editing any field clears stale HTTP refusal`() {
        val changes = listOf<(String) -> Unit>(model::onUsernameChange, model::onDisplayNameChange, model::onPasswordChange, model::onConfirmPasswordChange)
        for (change in changes) {
            api.response = Response.error(409, """{"error":"Bootstrap required"}""".toResponseBody("application/json".toMediaType()))
            fill()
            model.submit()
            assertEquals(RegisterMessage.FromServer("Bootstrap required"), model.state.value.message)
            change("updated")
            assertNull(model.state.value.message)
        }
        assertEquals(4, api.calls)
    }

    @Test fun `transport causes clear submitting without creating success or a session`() {
        val cases = listOf(
            SocketTimeoutException("timeout") to RegisterMessage.Timeout,
            SSLException("tls") to RegisterMessage.Tls,
            SerializationException("unreadable") to RegisterMessage.UnreadableBody,
            UnknownHostException("no route") to RegisterMessage.Unreachable,
            IOException("other") to RegisterMessage.Unknown,
        )
        for ((failure, expected) in cases) {
            model.reset()
            api.failure = failure
            fill()
            model.submit()
            assertEquals(expected, model.state.value.message)
            assertFalse(model.state.value.submitting)
            assertNull(model.state.value.successMessage)
            assertEquals("senha12345", model.state.value.password)
        }
        assertEquals(cases.size, api.calls)
    }

    @Test fun `transport failure clears on edit and permits a new submission`() {
        api.failure = SocketTimeoutException("timeout")
        fill()
        model.submit()
        assertEquals(RegisterMessage.Timeout, model.state.value.message)
        model.onUsernameChange("another")
        assertNull(model.state.value.message)
        api.failure = null
        model.submit()
        assertEquals(2, api.calls)
        assertEquals("another", api.request?.username)
        assertEquals("received", model.state.value.successMessage)
    }

    @Test fun `duplicate submission is ignored while pending and reset ignores late transport failure`() {
        val pending = CompletableDeferred<Response<RegisterResponse>>()
        api.pending = pending
        fill()
        model.submit()
        model.submit()
        assertEquals(1, api.calls)
        assertFalse(model.state.value.canSubmit)
        model.reset()
        pending.completeExceptionally(SocketTimeoutException("late timeout"))
        assertEquals(RegisterViewModel.UiState(), model.state.value)
    }

    private companion object {
        const val NEUTRAL_MESSAGE = "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."
    }

    private class TestApi : AuthApi {
        var calls = 0
        var request: RegisterRequest? = null
        var pending: CompletableDeferred<Response<RegisterResponse>>? = null
        var failure: Throwable? = null
        var response: Response<RegisterResponse> = Response.success(RegisterResponse(message = "received"))
        fun rejectWithHeaders(code: Int, message: String, headers: Headers) {
            val raw = okhttp3.Response.Builder()
                .code(code).message("Refused").protocol(okhttp3.Protocol.HTTP_1_1)
                .request(okhttp3.Request.Builder().url("http://localhost/").build())
                .headers(headers).build()
            response = Response.error("""{"error":"$message"}""".toResponseBody("application/json".toMediaType()), raw)
        }
        override suspend fun register(body: RegisterRequest): Response<RegisterResponse> {
            calls++
            request = body
            failure?.let { throw it }
            return pending?.await() ?: response
        }
        override suspend fun login(body: LoginRequest): Response<LoginResponse> = error("unused")
        override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error("unused")
        override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error("unused")
        override suspend fun me(): Response<ProfileResponse> = error("unused")
        override suspend fun profile(): Response<ProfileResponse> = error("unused")
        override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error("unused")
        override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error("unused")
        override suspend fun setupStatus(): Response<SetupStatusResponse> = error("unused")
    }

    private class TestTokens : TokenStore {
        override fun accessToken(): String? = null
        override fun refreshToken(): String? = null
        override fun role(): String? = null
        override fun save(accessToken: String, refreshToken: String) = Unit
        override fun saveRole(role: String) = Unit
        override fun clear() = Unit
    }
}
