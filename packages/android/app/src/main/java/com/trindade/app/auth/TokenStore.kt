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
     * The role the current session was opened with, or null when no role has been recorded.
     *
     * Null is not the same as no session, and a caller must not read it that way: a session stored before this
     * app recorded roles has its token pair and no role, so this answers null while [accessToken] answers a
     * token. That is the upgrade path -- an app updated from an earlier version -- and it resolves at the next
     * sign-in, which records the role the server hands over.
     *
     * Beside the tokens rather than in a store of its own, because it is the same fact: a session is what
     * the server granted at sign-in, and the role came with it. Two stores for one session is how the two
     * get to disagree.
     */
    fun role(): String?

    /**
     * Replace both tokens. Called on login and on every refresh, because the server rotates the pair
     * and the old refresh token stops being valid the moment the new one is issued.
     */
    fun save(accessToken: String, refreshToken: String)

    /**
     * Record the role the session was opened with. Separate from [save] because the pair rotates and the role
     * does not -- the refresh response carries no role, a rotation is the same operator -- so only sign-in
     * calls this.
     */
    fun saveRole(role: String)

    /**
     * Forget the session. Called on logout, and whenever a refresh fails: a store holding a token
     * that cannot be refreshed is worse than an empty one, because the interceptor would keep
     * offering it.
     */
    fun clear()
}
