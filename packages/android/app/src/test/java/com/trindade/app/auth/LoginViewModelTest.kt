package com.trindade.app.auth

import com.trindade.app.contract.models.AuthProfile
import com.trindade.app.contract.models.AuthUser
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LoginResponseUser
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.SetupRequest
import com.trindade.app.contract.models.SetupResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The login screen's transitions.
 *
 * The interesting part is not that signing in works, but what the screen does with each answer: the
 * server's own message is repeated rather than replaced, a host that cannot be reached says so instead
 * of blaming the credentials, and the password leaves the state as soon as it is no longer needed.
 */
class LoginViewModelTest {

    @Before
    fun installMainDispatcher() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `refuses to submit an empty form without asking the server`() {
        val api = FakeAuthApi()
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))

        assertEquals(false, model.state.value.canSubmit)
        model.submit()
        // The server would answer with a validation message the client already knows, and a round trip
        // to be told so is one the operator waits for.
        assertEquals(0, api.loginCalls)
    }

    @Test
    fun `repeats the server's own reason rather than inventing one`() {
        val api = FakeAuthApi(loginResponse = Response.error(401, refusalBody("Usuário inativo")))
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")
        model.submit()

        // The contract answers failures with a message, and it describes what happened better than
        // this client can: wrong credentials, an inactive account, or something unanticipated.
        assertEquals(LoginMessage.FromServer("Usuário inativo"), model.state.value.message)
        assertEquals(false, model.state.value.signedIn)
    }

    @Test
    fun `says the server is unreachable rather than blaming the credentials`() {
        val api = FakeAuthApi(failWithTransport = true)
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")
        model.submit()

        // Asserted as the type rather than as text: no server answered, so there are no server words
        // to repeat, and that is a different statement from a refusal this app has wording for.
        assertEquals(LoginMessage.Unreachable, model.state.value.message)
        assertEquals(false, model.state.value.signedIn)
    }

    @Test
    fun `clears the password from the state once signed in`() {
        val store = FakeTokenStore()
        val model = LoginViewModel(AuthRepository(FakeAuthApi(), store, json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")
        model.submit()

        assertEquals(true, model.state.value.signedIn)
        // Nothing after this needs it, and a password held in state is a password held in a heap dump.
        assertEquals("", model.state.value.password)
        assertEquals("token", store.accessToken())
    }

    @Test
    fun `clears a stale refusal as the operator types`() {
        val api = FakeAuthApi(loginResponse = Response.error(401, refusalBody("Usuário inativo")))
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")
        model.submit()
        assertEquals(LoginMessage.FromServer("Usuário inativo"), model.state.value.message)

        model.onPasswordChange("outra")

        // A refusal that outlives the thing that caused it reads as a stale accusation.
        assertNull(model.state.value.message)
    }
}

private class FakeAuthApi(
    private val loginResponse: Response<LoginResponse> = Response.success(
        LoginResponse(
            token = "token",
            refreshToken = "refresh",
            expiresIn = 900,
            user = LoginResponseUser(id = 1, username = "ana", role = "Trabalhador"),
        ),
    ),
    private val failWithTransport: Boolean = false,
) : AuthApi {

    var loginCalls = 0
        private set

    override suspend fun login(body: LoginRequest): Response<LoginResponse> {
        loginCalls++
        if (failWithTransport) throw java.io.IOException("network down")
        return loginResponse
    }

    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error(NOT_USED)
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = error(NOT_USED)
    override suspend fun me(): Response<ProfileResponse> = error(NOT_USED)
    override suspend fun profile(): Response<ProfileResponse> = error(NOT_USED)
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error(NOT_USED)
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error(NOT_USED)
    override suspend fun setup(body: SetupRequest): Response<SetupResponse> = error(NOT_USED)
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error(NOT_USED)

    private companion object {
        const val NOT_USED = "this fake does not implement that call; add it when a test needs it"
    }
}

private class FakeTokenStore : TokenStore {
    private var access: String? = null
    private var refresh: String? = null

    override fun accessToken(): String? = access
    override fun refreshToken(): String? = refresh
    override fun save(accessToken: String, refreshToken: String) {
        access = accessToken
        refresh = refreshToken
    }
    override fun clear() {
        access = null
        refresh = null
    }
}

/** The same Json the app builds, so a test does not pass against a lenient parser the app lacks. */
private fun json() = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun String.toResponseBody() = this.toResponseBody("application/json".toMediaType())

/**
 * A failure body shaped like the contract's, which requires `error`.
 *
 * The first version of this carried only `message` and the tests failed for the wrong reason: the
 * envelope could not be decoded, the repository fell back to its generic sentence, and the assertion
 * about repeating the server's own words was measuring a fallback instead.
 */
private fun refusalBody(message: String) =
    """{"error":"Unauthorized","message":"$message"}""".toResponseBody()
