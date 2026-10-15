package com.trindade.app.auth

import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
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
import com.trindade.app.network.AuthApi
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Unit tests for [RegisterViewModel].
 *
 * Verifies form validation (empty fields, password mismatch, minimum length),
 * safe password handling (memory cleared on success), neutral conditional response
 * presentation, and error mapping for 400, 409, 429 (with Retry-After), and transport failures.
 */
class RegisterViewModelTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refuses to submit empty form without asking the server`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        assertFalse(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(0, api.registerCalls)
    }

    @Test
    fun `refuses submit when passwords do not match`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("Joao Silva")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("outrasenha")

        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(0, api.registerCalls)
        assertEquals(RegisterMessage.Validation("As senhas não coincidem."), viewModel.state.value.message)
    }

    @Test
    fun `refuses submit when password is shorter than 8 characters`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("Joao Silva")
        viewModel.onPasswordChange("curta")
        viewModel.onConfirmPasswordChange("curta")

        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(0, api.registerCalls)
        assertEquals(
            RegisterMessage.Validation("A senha deve ter pelo menos 8 caracteres."),
            viewModel.state.value.message,
        )
    }

    @Test
    fun `refuses username longer than 50 characters without asking the server`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("u".repeat(51))
        viewModel.onDisplayNameChange("Usuario")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")

        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(0, api.registerCalls)
        assertEquals(
            RegisterMessage.Validation("O nome de usuário deve ter no máximo 50 caracteres."),
            viewModel.state.value.message,
        )
    }

    @Test
    fun `accepts username of exactly 50 characters and measures the trimmed value`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        // 50 characters and the padding around them: the server trims before it bounds, and so does this.
        viewModel.onUsernameChange("  " + "u".repeat(50) + "  ")
        viewModel.onDisplayNameChange("Usuario")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")

        viewModel.submit()

        assertEquals(1, api.registerCalls)
        assertEquals("u".repeat(50), api.lastRequest?.username)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `refuses display name longer than 100 characters without asking the server`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("d".repeat(101))
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")

        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(0, api.registerCalls)
        assertEquals(
            RegisterMessage.Validation("O nome de exibição deve ter no máximo 100 caracteres."),
            viewModel.state.value.message,
        )
    }

    @Test
    fun `accepts display name of exactly 100 characters`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("d".repeat(100))
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")

        viewModel.submit()

        assertEquals(1, api.registerCalls)
        assertEquals("d".repeat(100), api.lastRequest?.displayName)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `refuses password longer than 72 characters without asking the server`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("Joao Silva")
        viewModel.onPasswordChange("p".repeat(73))
        viewModel.onConfirmPasswordChange("p".repeat(73))

        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(0, api.registerCalls)
        assertEquals(
            RegisterMessage.Validation("A senha deve ter no máximo 72 caracteres."),
            viewModel.state.value.message,
        )
    }

    @Test
    fun `accepts password of exactly 72 characters`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("Joao Silva")
        viewModel.onPasswordChange("p".repeat(72))
        viewModel.onConfirmPasswordChange("p".repeat(72))

        viewModel.submit()

        assertEquals(1, api.registerCalls)
        assertEquals("p".repeat(72), api.lastRequest?.password)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `refuses multibyte password over 72 UTF-8 bytes while under 72 characters`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        // 25 euro signs: 25 characters, 75 UTF-8 bytes. Only the byte limit catches this one.
        val password = "€".repeat(25)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("Joao Silva")
        viewModel.onPasswordChange(password)
        viewModel.onConfirmPasswordChange(password)

        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(0, api.registerCalls)
        assertEquals(
            RegisterMessage.Validation("A senha deve ter no máximo 72 bytes."),
            viewModel.state.value.message,
        )
    }

    @Test
    fun `accepts multibyte password of exactly 72 UTF-8 bytes`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        // 24 euro signs: 24 characters, exactly 72 UTF-8 bytes.
        val password = "€".repeat(24)

        viewModel.onUsernameChange("joao")
        viewModel.onDisplayNameChange("Joao Silva")
        viewModel.onPasswordChange(password)
        viewModel.onConfirmPasswordChange(password)

        viewModel.submit()

        assertEquals(1, api.registerCalls)
        assertEquals(password, api.lastRequest?.password)
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `submits valid form and updates state with success message while dropping passwords from memory`() {
        val neutralMessage =
            "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."
        val api = FakeRegisterAuthApi(
            registerResponse = Response.success(RegisterResponse(message = neutralMessage)),
        )
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("  joao  ")
        viewModel.onDisplayNameChange("  Joao Silva  ")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")

        assertTrue(viewModel.state.value.canSubmit)
        viewModel.submit()

        assertEquals(1, api.registerCalls)
        assertEquals("joao", api.lastRequest?.username)
        assertEquals("Joao Silva", api.lastRequest?.displayName)
        assertEquals("senha12345", api.lastRequest?.password)

        val state = viewModel.state.value
        assertFalse(state.submitting)
        assertEquals(neutralMessage, state.successMessage)
        assertNull(state.message)
        // Passwords must be discarded from memory upon success
        assertEquals("", state.password)
        assertEquals("", state.confirmPassword)
        assertFalse(state.canSubmit)
    }

    @Test
    fun `duplicate username receives same neutral conditional success`() {
        val neutralMessage =
            "Solicitação de cadastro recebida. Se o acesso for aprovado pelo administrador, a conta será ativada. Entre em contato com a administração se não conseguir acessar."
        val api = FakeRegisterAuthApi(
            registerResponse = Response.success(RegisterResponse(message = neutralMessage)),
        )
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("existing_user")
        viewModel.onDisplayNameChange("Existing User")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")

        viewModel.submit()

        assertEquals(1, api.registerCalls)
        assertEquals(neutralMessage, viewModel.state.value.successMessage)
    }

    @Test
    fun `shows the Portuguese 400 sentence when the server answers with the English validation default`() {
        val errorJson = """{"error":"Invalid input"}""".toResponseBody("application/json".toMediaType())
        val api = FakeRegisterAuthApi(registerResponse = Response.error(400, errorJson))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("bad")
        viewModel.onDisplayNameChange("Bad")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        val state = viewModel.state.value
        assertFalse(state.submitting)
        assertNull(state.successMessage)
        assertEquals(
            RegisterMessage.FromServer("Dados de cadastro inválidos. Verifique as informações e tente novamente."),
            state.message,
        )
    }

    @Test
    fun `handles 409 bootstrap required rejection from server`() {
        val errorJson =
            """{"error":"A configuração inicial do administrador é necessária antes de registrar usuários."}"""
                .toResponseBody("application/json".toMediaType())
        val api = FakeRegisterAuthApi(registerResponse = Response.error(409, errorJson))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("first")
        viewModel.onDisplayNameChange("First")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        val state = viewModel.state.value
        assertFalse(state.submitting)
        assertNull(state.successMessage)
        assertEquals(
            RegisterMessage.FromServer("A configuração inicial do administrador é necessária antes de registrar usuários."),
            state.message,
        )
    }

    @Test
    fun `handles 429 rate limit with Retry-After seconds`() {
        val errorJson =
            """{"error":"Limite de solicitações excedido. Tente novamente mais tarde."}"""
                .toResponseBody("application/json".toMediaType())
        val headers = Headers.Builder().add("Retry-After", "90").build()
        val rawResponse = okhttp3.Response.Builder()
            .code(429)
            .message("Too Many Requests")
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .request(okhttp3.Request.Builder().url("http://localhost/").build())
            .headers(headers)
            .build()
        val api = FakeRegisterAuthApi(registerResponse = Response.error(errorJson, rawResponse))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("flooder")
        viewModel.onDisplayNameChange("Flooder")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        val state = viewModel.state.value
        assertFalse(state.submitting)
        assertNull(state.successMessage)
        assertEquals(
            RegisterMessage.RateLimited("Limite de solicitações excedido. Tente novamente mais tarde.", 90),
            state.message,
        )
    }

    @Test
    fun `handles timeout transport failure`() {
        val api = FakeRegisterAuthApi(transportFailure = SocketTimeoutException("timeout"))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("user")
        viewModel.onDisplayNameChange("User")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        assertEquals(RegisterMessage.Timeout, viewModel.state.value.message)
    }

    @Test
    fun `handles TLS transport failure`() {
        val api = FakeRegisterAuthApi(transportFailure = SSLException("tls error"))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("user")
        viewModel.onDisplayNameChange("User")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        assertEquals(RegisterMessage.Tls, viewModel.state.value.message)
    }

    @Test
    fun `handles unreadable body transport failure`() {
        val api = FakeRegisterAuthApi(transportFailure = SerializationException("corrupted"))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("user")
        viewModel.onDisplayNameChange("User")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        assertEquals(RegisterMessage.UnreadableBody, viewModel.state.value.message)
    }

    @Test
    fun `handles no route transport failure`() {
        val api = FakeRegisterAuthApi(transportFailure = UnknownHostException("no route"))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("user")
        viewModel.onDisplayNameChange("User")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        assertEquals(RegisterMessage.Unreachable, viewModel.state.value.message)
    }

    @Test
    fun `handles unknown transport failure`() {
        val api = FakeRegisterAuthApi(transportFailure = IOException("disk full"))
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("user")
        viewModel.onDisplayNameChange("User")
        viewModel.onPasswordChange("senha12345")
        viewModel.onConfirmPasswordChange("senha12345")
        viewModel.submit()

        assertEquals(RegisterMessage.Unknown, viewModel.state.value.message)
    }

    @Test
    fun `typing in any field clears stale error message`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("u")
        viewModel.onDisplayNameChange("d")
        viewModel.onPasswordChange("pass1")
        viewModel.onConfirmPasswordChange("pass2")
        viewModel.submit()
        assertEquals(RegisterMessage.Validation("As senhas não coincidem."), viewModel.state.value.message)

        viewModel.onConfirmPasswordChange("pass1")
        assertNull(viewModel.state.value.message)

        viewModel.submit()
        assertEquals(
            RegisterMessage.Validation("A senha deve ter pelo menos 8 caracteres."),
            viewModel.state.value.message,
        )

        viewModel.onUsernameChange("u2")
        assertNull(viewModel.state.value.message)
    }

    @Test
    fun `reset restores empty initial state`() {
        val api = FakeRegisterAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json)
        val viewModel = RegisterViewModel(repository)

        viewModel.onUsernameChange("u")
        viewModel.onDisplayNameChange("d")
        viewModel.reset()

        assertEquals(RegisterViewModel.UiState(), viewModel.state.value)
    }

    private class FakeRegisterAuthApi(
        private val registerResponse: Response<RegisterResponse> = Response.success(
            RegisterResponse(message = "ok"),
        ),
        private val transportFailure: Throwable? = null,
    ) : AuthApi {
        var registerCalls = 0
            private set
        var lastRequest: RegisterRequest? = null
            private set

        override suspend fun register(body: RegisterRequest): Response<RegisterResponse> {
            registerCalls++
            lastRequest = body
            transportFailure?.let { throw it }
            return registerResponse
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
}
