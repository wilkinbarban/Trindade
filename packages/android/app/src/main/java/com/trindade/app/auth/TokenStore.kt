package com.trindade.app.auth

/**
 * Where the session tokens live.
 *
 * An interface rather than a class for one concrete reason: the Android Keystore does not exist in a
 * JVM unit test, and the interceptor's single-flight guarantee is the part of D2 most worth testing.
 * With this seam, a fake store makes that guarantee observable without a device.
 */
interface TokenStore {

    /** The current access token, or null when there is no session. */
    fun accessToken(): String?

    /** The current refresh token, or null when there is no session. */
    fun refreshToken(): String?

    /**
     * Replace both tokens. Called on login and on every refresh, because the server rotates the pair
     * and the old refresh token stops being valid the moment the new one is issued.
     */
    fun save(accessToken: String, refreshToken: String)

    /**
     * Forget the session. Called on logout, and whenever a refresh fails: a store holding a token
     * that cannot be refreshed is worse than an empty one, because the interceptor would keep
     * offering it.
     */
    fun clear()
}
