package com.trindade.app.auth

import com.trindade.app.contract.models.*
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
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

    private class TestApi : AuthApi {
        var calls = 0
        var request: RegisterRequest? = null
        var pending: CompletableDeferred<Response<RegisterResponse>>? = null
        override suspend fun register(body: RegisterRequest): Response<RegisterResponse> {
            calls++
            request = body
            return pending?.await() ?: Response.success(RegisterResponse(message = "received"))
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
