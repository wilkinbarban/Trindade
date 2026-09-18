package com.trindade.app.auth

import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.network.AuthApi
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.runBlocking

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
) {

    /** Returns whether the session was established. A failure leaves any existing session alone. */
    suspend fun login(username: String, password: String): Boolean {
        val response = runCatching { api.login(LoginRequest(username = username, password = password)) }.getOrNull()
        val body = response?.body()
        if (response?.isSuccessful != true || body == null) return false

        tokenStore.save(body.token, body.refreshToken)
        return true
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
            runCatching { api.logout(LogoutRequest(refreshToken = refreshToken)) }
        }
    }

    /** Rotates the session, storing the new pair. The server invalidates the one presented. */
    suspend fun refresh(): Boolean {
        val refreshToken = tokenStore.refreshToken() ?: return false

        val response = runCatching { api.refresh(RefreshRequest(refreshToken = refreshToken)) }.getOrNull()
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
}
