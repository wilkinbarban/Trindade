package com.trindade.app.auth

import com.trindade.app.R
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
import com.trindade.app.logging.AppLogger
import com.trindade.app.network.AuthApi
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.SerializationException
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
 *
 * A failure to reach the server is measured twice, and deliberately: once at the repository, where the
 * class is read out of the Throwable, and once at the screen, where that class becomes a sentence. The
 * second alone would pass on a client that showed the right words for the wrong reason, which is the
 * shape the defect had -- every Throwable collapsed into one value before anybody could read it.
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
    fun `repeats a refusal that carries only the error field`() {
        // The shape most of this backend's refusals have. `auth.routes.ts` sends `error` for every one
        // of them and never a `message` at all -- the wrong-current-password refusal is
        // `{ error: 'Invalid current password' }` and nothing more -- so a client that reads `message`
        // alone answers the generic sentence to all of them, and the operator is told that signing in
        // failed instead of why.
        val api = FakeAuthApi(loginResponse = Response.error(401, errorOnlyBody("Invalid credentials")))
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")
        model.submit()

        // Asserted as an equality against a different sentence than the fallback: if the envelope
        // failed to decode, this reads GENERIC_REJECTION and the test fails loudly rather than
        // measuring the fallback and calling it the behaviour.
        assertEquals(LoginMessage.FromServer("Invalid credentials"), model.state.value.message)
        assertEquals(false, model.state.value.signedIn)
    }

    @Test
    fun `a residue failure says only that signing in failed, and is logged with its throwable`() {
        // A plain IOException: the residue case, classified as Unknown and logged accordingly.
        val failure = IOException("network down")
        val api = FakeAuthApi(transportFailure = failure)
        val logger = RecordingAppLogger()
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json(), logger))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")
        model.submit()

        // Asserted as the type rather than as text: no server answered, so there are no server words
        // to repeat, and that is a different statement from a refusal this app has wording for.
        // The residue is asserted as Unknown and not as Unreachable, which is now the no-route sentence
        // and the only one about the network. A plain IOException has not been shown to involve a
        // network, so the residue deliberately no longer borrows that sentence. The name says the same
        // thing for the same reason: `Unknown` is the sentence that names no cause at all, while
        // `Unreachable` is now the no-route sentence and the only one about a network, so a test named
        // after `Unreachable` would point a reader at the very sentence this case exists to stop
        // borrowing.
        assertEquals(LoginMessage.Unknown, model.state.value.message)
        assertEquals(false, model.state.value.signedIn)

        // The log line captures the unclassified failure so logcat on the device preserves it.
        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals("AuthRepository", entry.tag)
        assertTrue(entry.message.contains("Unknown"))
        assertTrue(entry.message.contains("IOException: network down"))
        assertEquals(failure, entry.throwable)
    }

    // The classification, measured where it is made: each of these drives the repository's own login and
    // asserts the result it hands back. The screen's sentence is a consequence of that result, so a test of
    // the sentence alone would pass on a client that produced the right words for the wrong reason.

    @Test
    fun `a timeout is classified as a slow server rather than a missing host`() {
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = SocketTimeoutException("timeout")),
            FakeTokenStore(),
            json(),
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        // A timeout and a name that does not resolve used to produce the same value here, which is why
        // the operator's report was "Sem conexão com o servidor" for a server that had answered.
        assertEquals(LoginResult.Unreachable(UnreachableCause.Timeout), result)
    }

    @Test
    fun `a name that does not resolve is classified as no route`() {
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = UnknownHostException("trindademasas.duckdns.org")),
            FakeTokenStore(),
            json(),
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        assertEquals(LoginResult.Unreachable(UnreachableCause.NoRoute), result)
    }

    @Test
    fun `a refused connection is classified as no route`() {
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = ConnectException("Connection refused")),
            FakeTokenStore(),
            json(),
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        assertEquals(LoginResult.Unreachable(UnreachableCause.NoRoute), result)
    }

    @Test
    fun `a reset connection is classified as no route`() {
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = SocketException("Connection reset")),
            FakeTokenStore(),
            json(),
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        assertEquals(LoginResult.Unreachable(UnreachableCause.NoRoute), result)
    }

    @Test
    fun `a bare interrupted read is classified as a timeout too`() {
        // The second half of the pair the repository names: OkHttp raises this one on the paths where it
        // timed the call itself, and it is not a SocketTimeoutException.
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = InterruptedIOException("timeout")),
            FakeTokenStore(),
            json(),
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        assertEquals(LoginResult.Unreachable(UnreachableCause.Timeout), result)
    }

    @Test
    fun `a failed handshake is classified apart from a host that was not there`() {
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = SSLException("chain not trusted")),
            FakeTokenStore(),
            json(),
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        assertEquals(LoginResult.Unreachable(UnreachableCause.Tls), result)
    }

    @Test
    fun `a reply that cannot be read is classified as an unreadable body`() {
        // What the kotlinx converter throws out of a call whose reply arrived and did not parse, which is
        // the one failure that is an answer rather than a silence.
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = SerializationException("Unexpected JSON token")),
            FakeTokenStore(),
            json(),
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        assertEquals(LoginResult.Unreachable(UnreachableCause.UnreadableBody), result)
    }

    @Test
    fun `a residue exception is classified as unknown`() {
        val failure = IOException("disk error")
        val logger = RecordingAppLogger()
        val repository = AuthRepository(
            FakeAuthApi(transportFailure = failure),
            FakeTokenStore(),
            json(),
            logger,
        )

        val result = runBlocking { repository.login("ana", "segredo") }

        assertEquals(LoginResult.Unreachable(UnreachableCause.Unknown), result)
        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals("AuthRepository", entry.tag)
        assertTrue(entry.message.contains("Unknown"))
        assertTrue(entry.message.contains("IOException: disk error"))
        assertEquals(failure, entry.throwable)
    }

    // And the classes the screen has words for, measured where the words are chosen.

    @Test
    fun `a timeout reaches the screen as did not answer in time`() {
        val api = FakeAuthApi(transportFailure = SocketTimeoutException("timeout"))
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")

        model.submit()

        // Its own message rather than the unreachable one: that sentence tells the operator to check a
        // network, and here the network is the one thing that was working.
        assertEquals(LoginMessage.Timeout, model.state.value.message)
        assertEquals(false, model.state.value.signedIn)
    }

    @Test
    fun `a failed handshake reaches the screen as tls error`() {
        val api = FakeAuthApi(transportFailure = SSLException("certificate expired"))
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")

        model.submit()

        assertEquals(LoginMessage.Tls, model.state.value.message)
        assertEquals(false, model.state.value.signedIn)
    }

    @Test
    fun `an unreadable body reaches the screen as unreadable body error`() {
        val api = FakeAuthApi(transportFailure = SerializationException("Unexpected JSON token"))
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")

        model.submit()

        assertEquals(LoginMessage.UnreadableBody, model.state.value.message)
        assertEquals(false, model.state.value.signedIn)
    }

    @Test
    fun `a host that was not there reaches the screen as unreachable`() {
        val api = FakeAuthApi(transportFailure = UnknownHostException("trindademasas.duckdns.org"))
        val model = LoginViewModel(AuthRepository(api, FakeTokenStore(), json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")

        model.submit()

        assertEquals(LoginMessage.Unreachable, model.state.value.message)
        assertEquals(false, model.state.value.signedIn)
    }

    @Test
    fun `each app sentence comes from one map, so a swapped id fails here`() {
        // The five ids are pairwise different and each one is the expected constant, which is the whole
        // point: the sentence a message renders is chosen by resource id, so swapping two of them would
        // compile and pass every other test in this file while the screen quietly said the wrong thing.
        // `LoginMessage.FromServer` is the sixth case and is deliberately absent: it has no resource by
        // construction, since its sentence is the server's own text carried by the value.
        assertEquals(R.string.login_timeout, appSentenceOf(LoginMessage.Timeout))
        assertEquals(R.string.login_tls, appSentenceOf(LoginMessage.Tls))
        assertEquals(R.string.login_unreadable_body, appSentenceOf(LoginMessage.UnreadableBody))
        assertEquals(R.string.login_unreachable, appSentenceOf(LoginMessage.Unreachable))
        assertEquals(R.string.login_unknown, appSentenceOf(LoginMessage.Unknown))
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
    fun `a consumed sign-in leaves the next one visible again`() {
        val store = FakeTokenStore()
        val model = LoginViewModel(AuthRepository(FakeAuthApi(), store, json()))
        model.onUsernameChange("ana")
        model.onPasswordChange("segredo")
        model.submit()
        assertEquals(true, model.state.value.signedIn)

        // What the login screen does with the event as soon as the app has acted on it.
        model.consumeSignIn()
        assertEquals(false, model.state.value.signedIn)

        // The second sign-in of one process -- the one a password change sends the operator through, and
        // the flagship flow of this slice. The flag has to rise again here. Asserted as a change rather
        // than as a value, because a value is what the defect got right: without the consumption the flag
        // would already be true, this line would still read true, and the effect keyed on it would never
        // run again -- the operator left on the form with a live session in the store and every retry
        // minting another one on the server.
        model.onPasswordChange("nova")
        model.submit()
        assertEquals(true, model.state.value.signedIn)
        // The session itself is not what consuming touches: the operator is signed in until they say
        // otherwise, and only the report that they just did it is spent.
        assertEquals("token", store.accessToken())
    }

    @Test
    fun `consuming a sign-in that never happened changes nothing`() {
        val store = FakeTokenStore()
        val model = LoginViewModel(AuthRepository(FakeAuthApi(), store, json()))
        model.onUsernameChange("ana")

        model.consumeSignIn()

        // Consuming is the app saying "I have seen it". A form nobody has submitted has nothing to hand
        // over, so this reports no session, invents no message, and leaves what was typed where it was.
        assertEquals(false, model.state.value.signedIn)
        assertEquals("ana", model.state.value.username)
        assertNull(model.state.value.message)
        assertNull(store.accessToken())
    }

    @Test
    fun `signing in records the role the server issued, and signing out forgets it`() {
        val api = FakeAuthApi()
        val repository = AuthRepository(api, FakeTokenStore(), json())

        assertEquals(null, repository.sessionRole())
        runBlocking { repository.login("ana", "segredo") }
        assertEquals("Trabalhador", repository.sessionRole())
        runBlocking { repository.logout() }
        assertEquals(null, repository.sessionRole())
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
    /**
     * The Throwable [login] throws instead of answering, or null when it answers.
     *
     * An exception rather than a `failWithTransport` boolean, because the tests that measure the
     * classification have to say *which* failure they mean: a fake that could only say "the transport",
     * with the Throwable built inside it, would let every one of those tests pass while measuring one value.
     */
    private val transportFailure: Throwable? = null,
) : AuthApi {

    var loginCalls = 0
        private set

    override suspend fun login(body: LoginRequest): Response<LoginResponse> {
        loginCalls++
        transportFailure?.let { throw it }
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

/** The same Json the app builds, so a test does not pass against a lenient parser the app lacks. */
private fun json() = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun String.toResponseBody() = this.toResponseBody("application/json".toMediaType())

/**
 * A refusal shaped like the contract's envelope, which requires `error`.
 *
 * The first version of this carried only `message` and the tests failed for the wrong reason: the
 * envelope could not be decoded, the repository fell back to its generic sentence, and the assertion
 * about repeating the server's own words was measuring a fallback instead. `error` is therefore always
 * present here, so a body this helper produces is always one the client can read.
 *
 * The two fields are both carried on purpose, because a refusal that has a `message` is the case where
 * the client must prefer it; [errorOnlyBody] is the other case, and it is the common one.
 */
private fun refusalBody(message: String) =
    """{"error":"Unauthorized","message":"$message"}""".toResponseBody()

/**
 * The shape most refusals actually have: `error` alone, with no `message` to prefer.
 *
 * This is not a malformed body and must not be mistaken for one. Every refusal on `auth.routes.ts` is
 * this shape, so a client that decodes `message` only reads null here and reports the generic
 * sentence, which is the defect this body exists to catch.
 */
private fun errorOnlyBody(error: String) =
    """{"error":"$error"}""".toResponseBody()

private class RecordingAppLogger : AppLogger {
    data class Entry(val tag: String, val message: String, val throwable: Throwable?)
    val entries = mutableListOf<Entry>()
    override fun w(tag: String, message: String, throwable: Throwable?) {
        entries += Entry(tag, message, throwable)
    }
}
