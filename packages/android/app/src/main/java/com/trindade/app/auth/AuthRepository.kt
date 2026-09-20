package com.trindade.app.auth

import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.ErrorEnvelope
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.UpdateProfileRequest
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
     * Ends the session locally, and asks the server to end it too.
     *
     * The request goes **first** and the store is cleared **unconditionally last**, and the order is
     * the whole of this method. `/api/auth/logout` is behind `fastify.authenticate`, and the access
     * token it authenticates with is read by [AuthInterceptor] from this same store -- so clearing
     * first sent the call with no `Authorization` header at all, and the server answered 401 without
     * ever reaching `revokeSession`. That is what made this client no better than the SPA, whose
     * logout passes no body and revokes nothing either: the refresh token was dropped on the phone
     * and stayed live on the server. Sending it while the session is still in place is what makes the
     * refresh token in the body mean anything.
     *
     * The clear is unconditional, and it happens in a `finally` for a reason that cost a review to find:
     * the call above can now be **cancelled**, and a cancellation leaves this function by design. Written
     * after the call instead, a cancelled revoke would skip the clear entirely -- the operator taps "Sair",
     * the request dies with the screen, and the tokens stay in the Keystore: a phone that is still signed
     * in after being told to sign out. The contract makes logout idempotent -- an unknown or already-ended
     * token still succeeds -- so a failed call means the session is already gone server-side, and there is
     * nothing a client gains by holding a token whose session is over. Reporting the failure as "still
     * signed in" would be the less true answer.
     */
    suspend fun logout() {
        val refreshToken = tokenStore.refreshToken()
        try {
            if (refreshToken != null) {
                runCatchingCancellable { api.logout(LogoutRequest(refreshToken = refreshToken)) }
            }
        } finally {
            tokenStore.clear()
        }
    }

    /**
     * Forgets the session locally without asking the server to end it.
     *
     * The one caller is a successful password change, and that call has already revoked **every**
     * session of this user on the server -- the caller's included. A logout from here would be a second
     * request whose only effect is an audit entry for a logout that did not happen, and it would be sent
     * with a token the server has just invalidated. [logout] stays the way to end a session that is
     * still live.
     */
    fun forgetSession() {
        tokenStore.clear()
    }

    /**
     * The signed-in operator's own profile, or null when the server cannot be asked.
     *
     * Null rather than a result type, which is what every read in this app answers. There is no business
     * answer to tell apart from a failure here: the endpoint has one refusal, `User not found`, and it
     * would say the same thing to every caller, so a result type would only give the screen a second way
     * to spell one sentence.
     */
    suspend fun profile(): ProfileResponseUser? =
        runCatchingCancellable { api.profile() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.user

    /**
     * Saves the operator's display name, answering with the user the server stored.
     *
     * The PATCH answers the updated user, so the caller takes that as the new truth and does not refetch:
     * a second read could only agree with, or contradict, the row this call has just written.
     *
     * Only `display_name` is sent. The request schema is `.strict()`, so any other key this body might
     * have carried -- `username`, `role` -- is refused with a 400 rather than quietly ignored, which is
     * why the request type has no other field and this method takes one string.
     */
    suspend fun saveDisplayName(displayName: String): ProfileUpdateResult {
        val response = runCatchingCancellable {
            api.updateProfile(UpdateProfileRequest(displayName = displayName))
        }.getOrNull() ?: return ProfileUpdateResult.Unreachable

        val user = response.body()?.user
        if (response.isSuccessful && user != null) return ProfileUpdateResult.Saved(user)
        return ProfileUpdateResult.Refused(response.code())
    }

    /**
     * Changes the operator's password.
     *
     * Shaped like [LoginResult] because it is the same kind of answer, and the refusal's text matters
     * for the same reason: a wrong current password answers `{ error: 'Invalid current password' }`
     * with no `message` at all, and [errorMessage] is what turns that into the sentence the operator
     * has to act on. Reporting it as a generic failure would leave them with nothing to correct.
     *
     * A 503 arrives when the database has no session store, a status the contract does not document;
     * it carries an `error` sentence as well, so it is repeated like any other refusal.
     *
     * A success revokes every session of this user on the server, including the one making this call,
     * so the caller has to end the local session rather than carry on.
     */
    suspend fun changePassword(current: String, new: String): PasswordChangeResult {
        val response = runCatchingCancellable {
            api.changePassword(ChangePasswordRequest(currentPassword = current, newPassword = new))
        }.getOrNull() ?: return PasswordChangeResult.Unreachable

        if (response.isSuccessful) return PasswordChangeResult.Changed

        return PasswordChangeResult.Rejected(response.errorMessage() ?: fallbackRefusal(response.code()))
    }

    /**
     * A sentence for a refusal whose envelope carried nothing this client can read.
     *
     * Only the wrong-current-password case is worth its own words. Everything else the endpoint can
     * answer is either documented with a message or is a payload this client built, and naming a cause
     * for it would be inventing one.
     */
    private fun fallbackRefusal(statusCode: Int): String = when (statusCode) {
        400 -> CHECK_CURRENT_PASSWORD
        else -> GENERIC
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
        const val GENERIC = "Não foi possível concluir. Tente de novo."
        const val CHECK_CURRENT_PASSWORD = "Confira a senha atual e tente de novo."
    }
}

/**
 * What saving the profile produced.
 *
 * [ReportWriteResult]'s shape, for the same reason: the PATCH answers with the updated user, so a caller
 * that grew its own copy must take the server's, and a refusal -- 400 for a payload the strict schema
 * rejects, 404 for an account that no longer exists -- is a business answer that needs different words
 * from an unreachable host. The status is carried rather than a sentence, because only the screen knows
 * what to say about each.
 */
sealed interface ProfileUpdateResult {
    data class Saved(val user: ProfileResponseUser) : ProfileUpdateResult
    data class Refused(val statusCode: Int) : ProfileUpdateResult
    data object Unreachable : ProfileUpdateResult
}

/**
 * What a password change produced.
 *
 * [LoginResult]'s shape because it is the same kind of answer: the server either agreed, refused with a
 * reason worth repeating, or was not there to answer at all. [Rejected.message] is the server's own
 * sentence whenever it sent a readable one, because a wrong current password is the operator's to fix
 * and only the server knows that is what happened.
 */
sealed interface PasswordChangeResult {
    data object Changed : PasswordChangeResult
    data class Rejected(val message: String) : PasswordChangeResult
    data object Unreachable : PasswordChangeResult
}
