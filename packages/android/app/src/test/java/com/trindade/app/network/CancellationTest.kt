package com.trindade.app.network

import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.TokenStore
import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.SetupRequest
import com.trindade.app.contract.models.SetupResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.loading.FakeLoadingApi
import com.trindade.app.loading.LoadingRepository
import com.trindade.app.reports.FakeReportsApi
import com.trindade.app.reports.ReportsRepository
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.Response

/**
 * [runCatchingCancellable] and the repository sites that call it.
 *
 * The first three tests are about the helper, and each one is written as two outcomes rather than as
 * one: a call that throws and a call that returns a failed [Result] are different things, and only one
 * of them is the right answer at a site that wraps a suspending call. A test that only asserted "the
 * answer was not null" would pass against the defect, because a captured cancellation answers null
 * just as a refused read does, so these tests fail loudly on the answer instead of inspecting it.
 *
 * The last four are about the wiring, since a correct helper that nothing calls fixes nothing. Each
 * drives one of the three repositories whose API throws a cancellation and asserts that it comes back
 * out rather than arriving as `null` or as `Rejected`. They also happen to be the proof that the
 * helper can be inlined around a suspending call at all: that is the only reason these call sites
 * compile.
 */
class CancellationTest {

    @Test
    fun `rethrows a cancellation rather than answering a failed result`() {
        val cancellation = CancellationException("the operator left the screen")

        try {
            val result = runCatchingCancellable { throw cancellation }
            fail("the cancellation came back as $result instead of being rethrown")
        } catch (thrown: CancellationException) {
            // The same instance, because nothing here is allowed to replace or wrap it: the caller that
            // gave up is waiting for exactly the cancellation it is handed back.
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `still captures a failure that is not a cancellation`() {
        val failure = IOException("no route to host")

        val result = runCatchingCancellable { throw failure }

        // Captured rather than thrown, which is the behaviour being preserved. The screens above read a
        // transport failure as "the server could not be asked", and turning that into a crash would be a
        // second defect in the opposite direction rather than a fix for this one.
        assertSame(failure, result.exceptionOrNull())
    }

    @Test
    fun `leaves a success a success`() {
        val result = runCatchingCancellable { "CRONOGRAMA DE CARREGAMENTO" }

        assertTrue(result.isSuccess)
        assertEquals("CRONOGRAMA DE CARREGAMENTO", result.getOrNull())
    }

    @Test
    fun `the loading history rethrows a cancelled read instead of answering null`() = runTest {
        val cancellation = CancellationException("the operator left the screen")
        val repository = LoadingRepository(FakeLoadingApi(historyFailure = cancellation))

        try {
            val answer = repository.history(date = null, month = null, page = null, pageSize = null)
            fail("the read came back as $answer instead of letting the cancellation through")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `the report history rethrows a cancelled read instead of answering null`() = runTest {
        val cancellation = CancellationException("the operator left the screen")
        val repository = ReportsRepository(FakeReportsApi(historyFailure = cancellation))

        try {
            val answer = repository.history(date = null, month = null, page = null, pageSize = null)
            fail("the read came back as $answer instead of letting the cancellation through")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `signing in rethrows a cancelled call instead of answering rejected`() = runTest {
        val cancellation = CancellationException("the operator left the screen")
        val repository = AuthRepository(CancellingAuthApi(cancellation), UnusedTokenStore(), json())

        try {
            val answer = repository.login(username = "ana", password = "segredo")
            fail("signing in came back as $answer instead of letting the cancellation through")
        } catch (thrown: CancellationException) {
            assertSame(cancellation, thrown)
        }
    }

    @Test
    fun `signing out clears the session even when the revoke call is cancelled`() = runTest {
        val cancellation = CancellationException("the operator left the screen")
        val store = SignOutTokenStore()
        val repository = AuthRepository(CancellingAuthApi(cancellation), store, json())

        try {
            repository.logout()
            fail("signing out answered instead of letting the cancellation through")
        } catch (thrown: CancellationException) {
            // The same instance, as the wiring tests above insist and for the same reason: the screen that
            // gave up is waiting for the cancellation it was handed, not for a replacement.
            assertSame(cancellation, thrown)
        }

        // The regression this pins: the clear was written *after* the revoke call, and the cancellation
        // that ends the screen left the method before reaching it -- so the operator taps "Sair", the
        // request dies with the screen, and the tokens stay in the Keystore. The phone is still signed in
        // after being told to sign out. The clear has to survive the cancellation, and this count is the
        // only thing that can tell the two shapes apart, since `logout` answers nothing.
        assertEquals(1, store.clears)
    }

    /** The same parser the app builds, although nothing here reaches a refusal body to decode. */
    private fun json() = Json { ignoreUnknownKeys = true }
}

/**
 * An `AuthApi` whose sign-in and sign-out throw the cancellation.
 *
 * Written here rather than reused because every other fake in this tree is private to the test file
 * that owns it, and this one serves the two session calls that are exercised here. Everything else
 * throws, following the convention the other fakes state: a quiet default is how a test passes while
 * calling something it never meant to.
 */
private class CancellingAuthApi(private val cancellation: CancellationException) : AuthApi {

    override suspend fun login(body: LoginRequest): Response<LoginResponse> = throw cancellation

    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> = error(NOT_USED)
    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> = throw cancellation
    override suspend fun me(): Response<ProfileResponse> = error(NOT_USED)
    override suspend fun profile(): Response<ProfileResponse> = error(NOT_USED)
    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> = error(NOT_USED)
    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> = error(NOT_USED)
    override suspend fun setup(body: SetupRequest): Response<SetupResponse> = error(NOT_USED)
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error(NOT_USED)
}

/**
 * A store nothing reaches: the sign-in above throws before the repository can read or write a token,
 * so any call arriving here is one this test would be wrong to make.
 */
private class UnusedTokenStore : TokenStore {
    override fun accessToken(): String? = error(NOT_USED)
    override fun refreshToken(): String? = error(NOT_USED)
    override fun save(accessToken: String, refreshToken: String) = error(NOT_USED)
    override fun clear() = error(NOT_USED)
}

/**
 * A store with a token to revoke, which counts the clears asked of it.
 *
 * The refresh token is what puts the cancelling sign-out call within reach: with nothing in the store the
 * repository never asks the server, and a test would be asserting about a call that was never made. The
 * count is the other half of that path and the only place the clear is visible at all, because
 * [AuthRepository.logout] answers nothing. `accessToken` and `save` throw, following [UnusedTokenStore]:
 * signing out reaches neither.
 */
private class SignOutTokenStore : TokenStore {

    /** How many times the sign-out path asked for the session to be dropped. */
    var clears = 0
        private set

    override fun accessToken(): String? = error(NOT_USED)
    override fun refreshToken(): String? = REFRESH_TOKEN
    override fun save(accessToken: String, refreshToken: String) = error(NOT_USED)
    override fun clear() {
        clears++
    }
}

private const val REFRESH_TOKEN = "refresh-token"
private const val NOT_USED = "this fake does not implement that call; add it when a test needs it"
