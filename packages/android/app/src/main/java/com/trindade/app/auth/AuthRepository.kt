package com.trindade.app.auth

import com.trindade.app.contract.models.ErrorEnvelope
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.network.AuthApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import retrofit2.Response

/**
 * Owns the session: signing in, signing out, and rotating.
 *
 * The interceptor needs a refresh it can call from a blocking thread, which is why [refreshBlocking]
 * exists alongside the suspending [refresh] rather than the interceptor reaching for the API itself.
 * Keeping the policy in the interceptor and the mechanics here is what lets each be reasoned about
 * on its own.
 */
@Singleton
class AuthRepository @Inject constructor(
    private val api: AuthApi,
    private val tokenStore: TokenStore,
    private val json: Json,
) {

    /**
     * Signs in, reporting what happened rather than only whether it worked.
     *
     * The failure text comes from the server's own refusal body, because the contract sends one for
     * every failure and it is the only description of what went wrong that the client cannot invent:
     * wrong credentials, an inactive account, or something unanticipated. An unreadable or absent
     * envelope falls back to a generic sentence rather than surfacing a parse error, which would tell
     * the user nothing and look like a client bug.
     */
    suspend fun login(username: String, password: String): LoginResult {
        val response = runCatchingCancellable { api.login(LoginRequest(username = username, password = password)) }.getOrNull()
            ?: return LoginResult.Unreachable

        val body = response.body()
        if (response.isSuccessful && body != null) {
            tokenStore.save(body.token, body.refreshToken)
            return LoginResult.Success
        }

        return LoginResult.Rejected(message = response.errorMessage() ?: GENERIC_REJECTION)
    }

    /**
     * Ends the session locally, and tells the server when it can.
     *
     * The local state is cleared first and unconditionally. The contract makes logout idempotent --
     * an unknown or already-ended token still succeeds -- so a failed call means the session is
     * already gone server-side, and there is nothing a client gains by holding a token whose session
     * is over. Reporting the failure as "still signed in" would be the less true answer.
     */
    suspend fun logout() {
        val refreshToken = tokenStore.refreshToken()
        tokenStore.clear()
        if (refreshToken != null) {
            runCatchingCancellable { api.logout(LogoutRequest(refreshToken = refreshToken)) }
        }
    }

    /** Rotates the session, storing the new pair. The server invalidates the one presented. */
    suspend fun refresh(): Boolean {
        val refreshToken = tokenStore.refreshToken() ?: return false

        val response = runCatchingCancellable { api.refresh(RefreshRequest(refreshToken = refreshToken)) }.getOrNull()
        val body = response?.body()
        if (response?.isSuccessful != true || body == null) return false

        tokenStore.save(body.token, body.refreshToken)
        return true
    }

    /**
     * Rotates the session from a blocking caller.
     *
     * `runBlocking` is deliberate rather than incidental: this is only ever called from an OkHttp
     * interceptor, which already runs on a thread whose whole job is to wait for one request.
     */
    fun refreshBlocking(): Boolean = runBlocking { refresh() }

    /** The access token the interceptor should attach, or null when there is no session. */
    fun accessToken(): String? = tokenStore.accessToken()

    /** Whether there is a session to resume, which is what decides between the login screen and the app. */
    fun hasSession(): Boolean = tokenStore.accessToken() != null

    /**
     * The server's message for a failed call, or null when it sent one this client cannot read.
     *
     * `message` first and `error` after it, because this backend uses one or the other and the two
     * are not interchangeable. `auth.routes.ts` sends `error` for every refusal it has and never a
     * `message` at all -- the wrong-current-password refusal is `{ error: 'Invalid current password' }`
     * and nothing more -- so decoding `message` alone turned each of them into the generic sentence,
     * which tells the operator that signing in failed and nothing about why.
     *
     * Both candidates are blank-tested, because an empty string is not a message either.
     *
     * Read through the same Json the rest of the app uses, so an unknown field in a future envelope
     * does not turn a legible refusal into a generic sentence.
     */
    private fun Response<*>.errorMessage(): String? {
        val raw = runCatching { errorBody()?.string() }.getOrNull()
        if (raw.isNullOrBlank()) return null
        val envelope = runCatching { json.decodeFromString<ErrorEnvelope>(raw) }.getOrNull() ?: return null
        return envelope.message?.takeIf { it.isNotBlank() } ?: envelope.error.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val GENERIC_REJECTION = "Não foi possível entrar. Verifique os dados e tente de novo."
    }
}
