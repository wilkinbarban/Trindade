package com.trindade.app.auth

import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.ErrorEnvelope
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.logging.AppLogger
import com.trindade.app.logging.NoOpAppLogger
import com.trindade.app.network.AuthApi
import com.trindade.app.network.runCatchingCancellable
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import retrofit2.Response

// The tag this file's one log line appears under, so a support call can be told what to grep for. Named
// after the class rather than after the screen, and declared the way `ProfileScreen`'s own tag is: the
// two log lines this app has stay readable one at a time, and logcat's `AuthRepository:D` filter reaches
// them without a second name to remember.
private const val TAG = "AuthRepository"

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
    private val logger: AppLogger = NoOpAppLogger,
) {

    /**
     * How many times the session's identity has changed: it has begun, or it has ended.
     *
     * What it counts is narrower than the name suggests, and the omission is the whole point. [login] moves
     * it once a session really began; [logout] and [forgetSession] move it once one has ended. [refresh]
     * deliberately does **not**, because rotating tokens is the same operator in the same session, and a
     * reader that took "the generation moved" for "somebody else is signed in" would read a rotation as a
     * change of operator. `ProfileViewModel` reads it for exactly that question, so a refresh counted here
     * would put back the leak this counter exists to close: it would treat the operator still sitting in
     * front of the screen as their predecessor. A rotation is not a new session; a login, a logout and a
     * forgotten session are.
     *
     * Monotone, so a reader compares the value it saw last with the value now and only ever asks whether the
     * two are equal -- nothing reads the number itself, and nothing else depends on its size. One counter for
     * the whole process, because this class is a singleton.
     *
     * [Volatile], because visibility and single-writer are two different claims and only one of them is the
     * language's. What the annotation guarantees is the half a plain `var` did not: a reader on another thread
     * -- `ProfileViewModel.open` reads this, and nothing in this declaration said which thread that was -- sees
     * the value some writer last wrote instead of a copy cached in its own thread. A stale read is exactly the
     * failure this counter exists to prevent: the entry would conclude the session had not changed and keep
     * the previous operator's account in front of the next one.
     *
     * What it does **not** buy is an atomic read-modify-write: `sessionGeneration++` is a read and a write, and
     * two threads doing it at once could lose one of the increments. That is not needed here, and the reason
     * is a stated convention about the call sites rather than a property of the type: all three writers --
     * [login], [logout] and [forgetSession] -- are reached from `ProfileViewModel`'s `viewModelScope`, which
     * runs on the main dispatcher, one user action at a time. [refresh] deliberately does not write at all. A
     * caller on another thread would need confinement or a `synchronized` block, and nothing here provides
     * one.
     */
    @Volatile
    var sessionGeneration: Int = 0
        private set

    /**
     * Signs in, reporting what happened rather than only whether it worked.
     *
     * The failure text comes from the server's own refusal body, because the contract sends one for
     * every failure and it is the only description of what went wrong that the client cannot invent:
     * wrong credentials, an inactive account, or something unanticipated. An unreadable or absent
     * envelope falls back to a generic sentence rather than surfacing a parse error, which would tell
     * the user nothing and look like a client bug.
     *
     * A failure that never reached the server is reported by its class and logged on the way out, and the
     * two halves are one correction rather than two features. `getOrNull()` collapsed every Throwable into
     * a single [LoginResult.Unreachable], so a timeout, a name that did not resolve, a refused connection
     * and a reply nobody could parse reached the operator as the same sentence about a missing network --
     * while the exception that said which one it had been was discarded without ever being written down.
     * Retrofit throws a transport failure out of the call instead of returning it as a status, which is
     * why the class can only be read from the Throwable.
     *
     * Every refusal path is untouched: a server answer still repeats the server's own text, and an
     * unreadable envelope still falls back to [GENERIC_REJECTION], because those cases did have an answer
     * and this change is about the ones that did not.
     */
    suspend fun login(username: String, password: String): LoginResult {
        val attempt = runCatchingCancellable {
            api.login(LoginRequest(username = username, password = password))
        }
        val response = attempt.getOrElse { failure ->
            val cause = failure.unreachableCause()
            // WARN rather than DEBUG or ERROR, and the level is a claim about the failure rather than
            // about its severity: the request failed and the app is reporting it, which is more than
            // routine typing (DEBUG) and less than something the app could not handle (ERROR) -- the
            // screen shows a sentence and the operator retries. It is also the level `ProfileScreen`'s
            // own unopenable-link line uses, so this app's logcat has one severity for "this did not
            // work" and no second vocabulary for it.
            //
            // The throwable is the third argument so its type, message and stack all reach logcat, and
            // the class is repeated in the text as well so that one grep finds the failure without
            // having to read frames: `Login failed without a usable answer: Timeout
            // (SocketTimeoutException: timeout)`. Neither the username nor the password is logged; the
            // cause and the exception are what a reader needs, and the credentials are not.
            //
            // The prefix says "without a usable answer" rather than "before the server answered", and
            // the difference is one of the values it now covers: `UnreadableBody` is an answer that
            // arrived and could not be parsed, so a prefix claiming the server never answered would be
            // false about it. Every value in the taxonomy shares the thing this wording claims -- the
            // attempt produced no answer this client could use -- and none of them shares the stronger
            // claim the old text made.
            logger.w(
                TAG,
                "Login failed without a usable answer: $cause " +
                    "(${failure.javaClass.simpleName}: ${failure.message})",
                failure,
            )
            return LoginResult.Unreachable(cause)
        }

        val body = response.body()
        if (response.isSuccessful && body != null) {
            tokenStore.save(body.token, body.refreshToken)
            // A session begins here, whoever the operator is: see [sessionGeneration] for why this is the
            // only beginning that counts, and why the rotation in [refresh] is not a second one.
            sessionGeneration++
            return LoginResult.Success
        }

        return LoginResult.Rejected(message = response.errorMessage() ?: GENERIC_REJECTION)
    }

    /**
     * Which kind of failure kept a request from producing an answer.
     *
     * The mapping is made once, here, and [UnreachableCause] carries the reasoning behind each value and
     * the classes it is read from. The order of the branches is not load-bearing -- the families do
     * not overlap, since `SSLException` is not a `SocketException` -- and it is the order a diagnosis gets
     * simpler in: the client's own clock first, then the address, then the transport.
     *
     * [UnreachableCause.Timeout] means the request did not answer in time (covering both OkHttp connect
     * timeout and read timeout). `SocketTimeoutException` and `ConnectException` are named although
     * `InterruptedIOException` and `SocketException` cover them. That is deliberate: the pair is what a reader
     * greps for on the device, and it says which classes this branch is about without the reader having to
     * know either hierarchy.
     *
     * [UnreachableCause.UnreadableBody] is strictly for deserialization failures (`kotlinx.serialization.SerializationException`),
     * while [UnreachableCause.Unknown] is the residue for any other unclassified Throwable -- whose own
     * type and message the log line carries, so the fallback is never the last word about what happened.
     */
    private fun Throwable.unreachableCause(): UnreachableCause = when (this) {
        is SocketTimeoutException, is InterruptedIOException -> UnreachableCause.Timeout
        is UnknownHostException, is ConnectException, is SocketException -> UnreachableCause.NoRoute
        is SSLException -> UnreachableCause.Tls
        is kotlinx.serialization.SerializationException -> UnreachableCause.UnreadableBody
        else -> UnreachableCause.Unknown
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
     * The clear is unconditional, and it happens in a `finally` for a reason that a review found: the
     * revoke call **inside this function** can be **cancelled**, and a cancellation leaves by design. Written
     * after the call instead, a cancelled revoke would skip the clear entirely -- the operator taps "Sair",
     * the request dies with the screen, and the tokens stay in the Keystore: a phone that is still signed in
     * after being told to sign out.
     *
     * **The trade is deliberate and it is worth stating plainly, because a review asked for it.** A revoke
     * that is cancelled *before it reaches the server* leaves that session alive while the client has
     * discarded the only token it could have retried with. So the old sentence -- that a failed call means
     * the session is already gone server-side -- is true for a call the server refused and false for one that
     * never arrived. Between a server session that expires on its own and a phone that stays signed in, this
     * chooses the phone: the device is the thing that changes hands in the yard, and an unrevoked session
     * with no token left to present is inert from the client's side.
     */
    suspend fun logout() {
        val refreshToken = tokenStore.refreshToken()
        try {
            if (refreshToken != null) {
                runCatchingCancellable { api.logout(LogoutRequest(refreshToken = refreshToken)) }
            }
        } finally {
            tokenStore.clear()
            // The session ends here whether the call arrived, was refused or was cancelled, and that is the
            // same conditional-ness the clear has: a generation left behind would go on describing the
            // session this just ended, and the entry that follows it would read the old operator's account
            // as its own.
            sessionGeneration++
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
        // A session that ended without a call to the server is still a session that ended: the password
        // change that calls this has already revoked every session of this user, this one included.
        sessionGeneration++
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
