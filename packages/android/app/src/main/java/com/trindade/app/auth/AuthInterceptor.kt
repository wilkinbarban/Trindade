package com.trindade.app.auth

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Attaches the access token, and turns a 401 into one refresh followed by one retry.
 *
 * Blocking rather than suspending, because OkHttp runs interceptors on its own threads: the
 * synchronisation below is therefore a lock, not a coroutine primitive, and wrapping it in a
 * coroutine would only add a scheduler between the HTTP call and its answer.
 *
 * [refreshSession] is a plain callback rather than a dependency on the repository, which keeps the
 * logic in this file about the refresh *policy* and not about how a refresh is performed. It is also
 * what lets the tests drive it directly.
 */
class AuthInterceptor(
    private val tokenStore: TokenStore,
    private val refreshSession: () -> Boolean,
) : Interceptor {

    private val refreshLock = ReentrantLock()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        // A request that already carries a header was built by something that knows better, and the
        // tokenless calls must not carry one at all.
        if (request.header(AUTHORIZATION) != null || isTokenless(request)) {
            return chain.proceed(request)
        }

        val attemptedToken = tokenStore.accessToken() ?: return chain.proceed(request)
        val response = chain.proceed(request.authorizedWith(attemptedToken))
        if (response.code != HTTP_UNAUTHORIZED) return response

        if (!refreshOnce(attemptedToken)) {
            // The refresh did not produce a usable token, so the session is over. Clearing here means
            // the next request does not offer a token that is known to be dead.
            tokenStore.clear()
            return response
        }

        val newToken = tokenStore.accessToken() ?: return response

        // Retried exactly once. A second 401 is returned to the caller rather than refreshed again,
        // because a refresh that succeeds and a retry that still fails is a different problem than
        // an expired token, and looping on it would hide that.
        response.close()
        return chain.proceed(request.authorizedWith(newToken))
    }

    /**
     * Refreshes at most once across every caller, and reports whether a usable token is in place.
     *
     * The lock plus the comparison against the token the caller actually used is what makes this
     * single-flight, and the comparison is the part that matters. Two requests that failed at the
     * same moment both reach here; the first refreshes, and the second finds the stored token already
     * changed and returns without calling refresh again. Rotating the pair twice is what the contract
     * treats as a leak: it revokes the whole session family, so a client that refreshes concurrently
     * does not merely waste a call, it signs the user out.
     */
    private fun refreshOnce(attemptedToken: String): Boolean = refreshLock.withLock {
        val current = tokenStore.accessToken()
        if (current != null && current != attemptedToken) return@withLock true
        refreshSession()
    }

    private fun isTokenless(request: Request): Boolean =
        TOKENLESS_PATHS.any { request.url.encodedPath.endsWith(it) }

    private fun Request.authorizedWith(token: String): Request =
        newBuilder().header(AUTHORIZATION, "Bearer $token").build()

    private companion object {
        const val AUTHORIZATION = "Authorization"
        const val HTTP_UNAUTHORIZED = 401

        /**
         * The calls that must never be sent with a token.
         *
         * Login and setup have no session to speak of. Refresh is the important one: it is
         * unauthenticated by design, so attaching a token achieves nothing, and presenting one that
         * was already rotated is exactly what the server reads as a leak. Excluding it here is also
         * what stops the interceptor from recursing into itself when the refresh itself answers 401.
         */
        val TOKENLESS_PATHS = listOf("/api/auth/login", "/api/auth/refresh", "/api/auth/setup")
    }
}
