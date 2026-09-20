package com.trindade.app.auth

import com.trindade.app.contract.models.ChangePasswordRequest
import com.trindade.app.contract.models.LoginRequest
import com.trindade.app.contract.models.LoginResponse
import com.trindade.app.contract.models.LogoutRequest
import com.trindade.app.contract.models.ProfileResponse
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.contract.models.RefreshRequest
import com.trindade.app.contract.models.RefreshResponse
import com.trindade.app.contract.models.SetupRequest
import com.trindade.app.contract.models.SetupResponse
import com.trindade.app.contract.models.SetupStatusResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.UpdateProfileRequest
import com.trindade.app.network.AuthApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The profile screen's transitions, and the two things it must not get wrong about the session.
 *
 * Most of these are about what the screen keeps and what it sends rather than about what it shows,
 * because the mistakes worth catching here are invisible in the state: a save that changes nothing is a
 * round trip the server writes an audit row for, and a sign-out whose local clear outran its server call
 * looks exactly like one that revoked the session until somebody notices the refresh token still works.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` runs on Main, which a JVM test has no
 * implementation of until one is installed.
 */
class ProfileViewModelTest {

    @Before
    fun installMainDispatcher() {
        // Unconfined so the coroutines run as they are launched and the assertions need no manual
        // advancing, which keeps the tests about the state rather than about the scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loads the profile and seeds the editable name`() {
        val model = profileViewModel()
        model.open()

        assertEquals("ana", model.state.value.profile?.username)
        // The copy the operator edits starts as the server's own name, so a save of an untouched form is
        // not a change at all.
        assertEquals("Ana Souza", model.state.value.displayName)
        assertEquals(false, model.state.value.loading)
        assertEquals(false, model.state.value.canSave)
    }

    @Test
    fun `a profile that could not be read says so and leaves nothing to edit`() {
        val api = StubAuthApi(profileResponse = Response.error(500, errorOnlyBody("Internal error")))
        val model = profileViewModel(api)
        model.open()

        // Null is "the server did not answer", which is a different statement from an empty profile, and
        // the screen has to be able to tell them apart: a form drawn over a profile that was never read
        // would offer a save carrying a name the operator never chose.
        assertNull(model.state.value.profile)
        assertEquals(ProfileViewModel.UNREACHABLE, model.state.value.message)
        assertEquals(false, model.state.value.canSave)
    }

    @Test
    fun `nothing changed, nothing blank and nothing to send`() {
        val api = StubAuthApi()
        val model = profileViewModel(api)
        model.open()
        assertEquals(false, model.state.value.canSave)

        model.onDisplayNameChange("   ")
        assertEquals(false, model.state.value.canSave)

        // Whitespace the server would trim is not a change either: the comparison is against the value
        // that would actually be sent.
        model.onDisplayNameChange("Ana Souza  ")
        assertEquals(false, model.state.value.canSave)

        model.onDisplayNameChange("Ana S. Souza")
        assertEquals(true, model.state.value.canSave)

        // And a gate is not a request: the tap that changes nothing sends nothing.
        model.onDisplayNameChange("Ana Souza")
        model.save()
        assertEquals(0, api.updateBodies.size)
    }

    @Test
    fun `a successful save takes the server's user as the truth and confirms it`() {
        // A name the client did not send, so "the answer is the new truth" is what the assertions below
        // actually measure rather than an accident of the stub echoing the request back.
        val api = StubAuthApi(
            updateResponse = Response.success(ProfileResponse(user = profileUser(displayName = "Ana S. Souza"))),
        )
        val model = profileViewModel(api)
        model.open()
        model.onDisplayNameChange("ana souza")
        model.save()

        // The PATCH answers the stored row, so that is the new truth and there is nothing left to fetch.
        assertEquals("Ana S. Souza", model.state.value.profile?.displayName)
        assertEquals("Ana S. Souza", model.state.value.displayName)
        assertEquals(1, api.profileCalls)
        assertEquals(true, model.state.value.nameSaved)
        assertNull(model.state.value.message)
        assertEquals(false, model.state.value.canSave)
    }

    @Test
    fun `the request carries only the display name, trimmed`() {
        val api = StubAuthApi()
        val model = profileViewModel(api)
        model.open()
        model.onDisplayNameChange("  Ana S. Souza  ")
        model.save()

        // The PATCH schema is `.strict()`: any other key -- `username`, `role` -- is a 400 rather than
        // something the server ignores. Asserted on the encoded body rather than on the DTO, because the
        // DTO having one field is the fact being relied on.
        assertEquals(
            """{"display_name":"Ana S. Souza"}""",
            json().encodeToString(UpdateProfileRequest.serializer(), api.updateBodies.single()),
        )
    }

    @Test
    fun `a refused save keeps the operator's text and says why`() {
        val api = StubAuthApi(updateResponse = Response.error(400, errorOnlyBody("Invalid input")))
        val model = profileViewModel(api)
        model.open()
        model.onDisplayNameChange("Ana S. Souza")
        model.save()

        // The name they typed is still in the field. A refusal is theirs to resolve, and clearing the
        // field in the act of telling them so would discard their work -- the lesson D4c recorded.
        assertEquals("Ana S. Souza", model.state.value.displayName)
        assertEquals(false, model.state.value.nameSaved)
        assertEquals("Confira o nome e tente de novo.", model.state.value.message)
    }

    @Test
    fun `changing the name as the operator types clears the previous answer`() {
        val api = StubAuthApi(updateResponse = Response.error(400, errorOnlyBody("Invalid input")))
        val model = profileViewModel(api)
        model.open()
        model.onDisplayNameChange("Ana S. Souza")
        model.save()
        assertEquals("Confira o nome e tente de novo.", model.state.value.message)

        model.onDisplayNameChange("Ana Souza Silva")

        // A refusal that outlives the thing that caused it reads as a stale accusation.
        assertNull(model.state.value.message)
    }

    @Test
    fun `the password change mirrors the eight-character rule`() {
        val model = profileViewModel()
        model.open()

        model.onCurrentPasswordChange("segredo")
        model.onNewPasswordChange("1234567")
        assertEquals(false, model.state.value.canChangePassword)

        // Eight is the server's minimum, and it is the same number the screen prints next to the field.
        model.onNewPasswordChange("12345678")
        assertEquals(true, model.state.value.canChangePassword)

        model.onCurrentPasswordChange("")
        // No length rule of its own for the current password, but it is still required: the endpoint
        // validates it as a string of at least one character.
        assertEquals(false, model.state.value.canChangePassword)
    }

    @Test
    fun `a successful change ends the session and empties both password fields`() {
        val store = StubTokenStore().also { it.save("token", "refresh") }
        val api = StubAuthApi()
        val model = profileViewModel(api, store)
        model.open()
        model.onCurrentPasswordChange("segredo")
        model.onNewPasswordChange("novasenha")
        model.changePassword()

        // The server revoked every session of this user, this one included, so the local session goes with
        // it -- and with no logout call: a request about a session that no longer exists would only add an
        // audit entry for a logout that did not happen.
        assertEquals(1, store.clears)
        assertEquals(0, api.logoutBodies.size)
        assertEquals(
            ChangePasswordRequest(currentPassword = "segredo", newPassword = "novasenha"),
            api.changePasswordBodies.single(),
        )
        assertEquals(true, model.state.value.signedOut)
        assertEquals(ProfileViewModel.PASSWORD_CHANGED, model.state.value.message)
        // Both leave the state as soon as they are not needed: a password held in state is a password
        // held in a heap dump.
        assertEquals("", model.state.value.currentPassword)
        assertEquals("", model.state.value.newPassword)
    }

    @Test
    fun `a refusal carrying only the error field reaches the operator as that sentence`() {
        // The shape `auth.routes.ts` sends for a wrong current password: `{ error: 'Invalid current
        // password' }` with no `message` at all. A client that reads `message` alone reports a generic
        // failure for the one refusal the operator has to act on.
        val api = StubAuthApi(changePasswordResponse = Response.error(400, errorOnlyBody("Invalid current password")))
        val model = profileViewModel(api)
        model.open()
        model.onCurrentPasswordChange("errada")
        model.onNewPasswordChange("novasenha")
        model.changePassword()

        // Asserted against a sentence that is neither this app's fallback nor its generic one: if the
        // envelope failed to decode, this reads the fallback and fails loudly rather than measuring it.
        assertEquals("Invalid current password", model.state.value.message)
        assertEquals(false, model.state.value.signedOut)
        // The refused password is still there, because it is theirs to correct.
        assertEquals("errada", model.state.value.currentPassword)
    }

    @Test
    fun `an unreachable change says so and does not sign the operator out`() {
        val store = StubTokenStore().also { it.save("token", "refresh") }
        val api = StubAuthApi(failChangePasswordWithTransport = true)
        val model = profileViewModel(api, store)
        model.open()
        model.onCurrentPasswordChange("segredo")
        model.onNewPasswordChange("novasenha")
        model.changePassword()

        assertEquals(ProfileViewModel.UNREACHABLE, model.state.value.message)
        // Nothing was changed, so the session is still real, and sending the operator back to the login
        // would misdescribe what happened.
        assertEquals(false, model.state.value.signedOut)
        assertEquals(0, store.clears)
    }

    @Test
    fun `signing out clears the session and sends the refresh token`() {
        val order = mutableListOf<String>()
        val store = StubTokenStore(onClear = { order += "clear" }).also { it.save("token", "refresh") }
        val api = StubAuthApi(onLogout = { order += "logout" })
        val model = profileViewModel(api, store)
        model.open()
        model.signOut()

        assertEquals(true, model.state.value.signedOut)
        assertEquals(1, store.clears)
        // The refresh token is what the server needs to end this device's session, and it is the whole of
        // what the SPA fails to send: its logout passes no body, so it revokes nothing server-side.
        assertEquals(LogoutRequest(refreshToken = "refresh"), api.logoutBodies.single())
        // The request goes first and the clear is last, which is not a detail: the logout route is
        // authenticated and the interceptor takes the access token from this store, so a store cleared
        // first sent the call with no token and the server answered 401 without revoking anything.
        assertEquals(listOf("logout", "clear"), order)
    }

    @Test
    fun `opening the screen again drops the previous session's leftovers`() {
        val store = StubTokenStore().also { it.save("token", "refresh") }
        val api = StubAuthApi()
        val model = profileViewModel(api, store)
        model.open()
        model.onCurrentPasswordChange("segredo")
        model.onNewPasswordChange("novasenha")
        model.changePassword()
        assertEquals(true, model.state.value.signedOut)

        model.open()

        // The view model is scoped to the activity, so it outlives the screen and the session: without this,
        // the panel would be waiting for whoever opened the profile next, in the following session.
        assertEquals(false, model.state.value.signedOut)
        assertNull(model.state.value.message)
        assertEquals("", model.state.value.currentPassword)
        // Opening reads the profile again rather than showing what the last session left behind.
        assertEquals(2, api.profileCalls)
    }

    @Test
    fun `a new session does not leave the previous operator's account readable while its own read is in flight`() {
        // The account of one operator, and then the entry of the next one. The view model is scoped to the
        // activity, so it outlives the session as well as the screen, and what is in state as that second
        // entry begins is the first operator's account. On a shared phone -- A signs out, B signs in and
        // opens Perfil -- the reader of the screen is B.
        val api = StubAuthApi(gateProfile = true)
        val model = profileViewModel(api)

        model.open()
        api.profileGates[0].complete(Unit)
        assertEquals("ana", model.state.value.profile?.username)

        // A's session ends here, through the same path the "Sair" action uses. `logout()` is one of the
        // operations that move the generation, so the entry below belongs to another session -- and that is
        // the only thing that lets it know to drop what is in state. Without it this entry would be the same
        // operator entering again and would keep A's account, which is the finding.
        model.signOut()

        // B's entry, with B's answer held open: this is the whole window the finding is about, and no race
        // is needed to stand in it.
        api.profileAnswer = Response.success(
            ProfileResponse(user = profileUser(username = "bruno", displayName = "Bruno Lima")),
        )
        model.open()
        assertEquals("one read per entry", 2, api.profileGates.size)

        // The claim, on the state rather than on the screen: the previous operator's account is not readable
        // while the new one is in flight. `displayName` is asserted with it because it is the account's own
        // copy -- the one the form is seeded from -- and not a transient of the screen.
        assertNull(
            "the previous operator's account is still readable while the new one's read is in flight",
            model.state.value.profile,
        )
        assertEquals("", model.state.value.displayName)
        // And a save is not offered against it either: A's name was what the gate was comparing with.
        assertEquals(false, model.state.value.canSave)

        // The read that is in flight is still the one that lands.
        api.profileGates[1].complete(Unit)
        assertEquals("bruno", model.state.value.profile?.username)
        assertEquals("Bruno Lima", model.state.value.displayName)
    }

    @Test
    fun `an entry in the same session keeps the account readable while its own read is in flight`() {
        // The other side of the trade the leak fix made, and the reason the entry asks the generation first:
        // nobody signed out and nobody signed in, so the account in state is this operator's own. Dropping it
        // here would blank the screen for the length of a read, when the read is the operator asking again for
        // the account already in front of them.
        val api = StubAuthApi(gateProfile = true)
        val model = profileViewModel(api)

        model.open()
        api.profileGates[0].complete(Unit)
        assertEquals("ana", model.state.value.profile?.username)

        // The same session, and the stored name has moved on since the last read.
        api.profileAnswer = Response.success(ProfileResponse(user = profileUser(displayName = "Ana S. Souza")))
        model.open()
        assertEquals("one read per entry", 2, api.profileGates.size)

        assertEquals("ana", model.state.value.profile?.username)
        assertEquals("Ana Souza", model.state.value.displayName)

        // And the answer still replaces it: what the entry keeps is a screen that stays useful during the
        // wait, not a copy that outranks the server.
        api.profileGates[1].complete(Unit)
        assertEquals("Ana S. Souza", model.state.value.profile?.displayName)
        assertEquals("Ana S. Souza", model.state.value.displayName)
    }

    @Test
    fun `an entry in the same session keeps a name the operator has typed but not saved`() {
        // R3-001, which is the price clearing on every entry was paying: the field is the only copy of a name
        // that has not been sent, so an entry that empties it throws away work the operator had done and had
        // not been told anything was wrong with.
        val api = StubAuthApi(gateProfile = true)
        val model = profileViewModel(api)

        model.open()
        api.profileGates[0].complete(Unit)
        model.onDisplayNameChange("Ana Souza Silva")

        model.open()

        assertEquals("Ana Souza Silva", model.state.value.displayName)
        // The account is kept with it, so the field is not left standing over nothing: with no profile in state
        // the save would not even be offered, and the typed name would be kept for no use at all.
        assertEquals("ana", model.state.value.profile?.username)
        assertEquals(true, model.state.value.canSave)

        // The answer seeds the field from the server when it lands, which is the pre-existing rule: what the
        // entry keeps is what is in front of the operator now, not a copy that outlives the read.
        api.profileGates[1].complete(Unit)
        assertEquals("Ana Souza", model.state.value.displayName)
    }

    @Test
    fun `a read that fails in the same session keeps the account and the name`() {
        // R4-2, the other half of the trade. The failure path already kept a name it could not replace, and
        // throwing the account away underneath it left an empty account block where the account read a moment
        // ago belongs -- on a session that has not changed, so the read that failed is no evidence about who is
        // signed in.
        val api = StubAuthApi(gateProfile = true)
        val model = profileViewModel(api)

        model.open()
        api.profileGates[0].complete(Unit)
        model.onDisplayNameChange("Ana Souza Silva")

        api.profileAnswer = Response.error(500, errorOnlyBody("Internal error"))
        model.open()
        api.profileGates[1].complete(Unit)

        assertEquals("ana", model.state.value.profile?.username)
        assertEquals("Ana Souza Silva", model.state.value.displayName)
        // The operator is still told the read failed: keeping the account is not a claim that it worked.
        assertEquals(ProfileViewModel.UNREACHABLE, model.state.value.message)
    }

    @Test
    fun `a rotated token is still the same session, so a re-entry keeps the account`() {
        // The distinction the generation exists for, from the side that would put the leak back: a refresh is
        // the same operator in the same session, so counting a rotation as a change would make the entry after
        // it drop the account of the operator who is still signed in and still looking at the screen. Driven
        // through the repository's own rotation -- the call that really writes the new pair -- rather than by
        // anything reaching into the counter.
        val store = StubTokenStore().also { it.save("token", "refresh") }
        val api = StubAuthApi(gateProfile = true)
        val (repository, model) = profileSession(api, store)

        model.open()
        api.profileGates[0].complete(Unit)
        assertEquals("ana", model.state.value.profile?.username)

        runBlocking { repository.refresh() }
        // The rotation really happened through the writing path, so the entry below is the one a rotation
        // precedes rather than one taken after a call that quietly answered false.
        assertEquals("rotated", store.accessToken())

        api.profileAnswer = Response.success(ProfileResponse(user = profileUser(displayName = "Ana S. Souza")))
        model.open()

        // Same operator, same session: the account on screen is still theirs, and only an answer may replace it.
        assertEquals("ana", model.state.value.profile?.username)
        assertEquals("Ana Souza", model.state.value.displayName)

        api.profileGates[1].complete(Unit)
        assertEquals("Ana S. Souza", model.state.value.displayName)
    }

    @Test
    fun `a read that fails after the session changed leaves no account and no name`() {
        // The failure path keeps what the entry left it, so what it keeps depends entirely on that entry. After
        // the session changed there is nothing of the previous operator's for it to present as this one's, and
        // this is the path where an account left in state would stay on screen for good rather than for the
        // length of a wait.
        val api = StubAuthApi(gateProfile = true)
        val model = profileViewModel(api)

        model.open()
        api.profileGates[0].complete(Unit)
        assertEquals("ana", model.state.value.profile?.username)

        // The session ends, through the same path the "Sair" action uses.
        model.signOut()

        api.profileAnswer = Response.error(500, errorOnlyBody("Internal error"))
        model.open()
        api.profileGates[1].complete(Unit)

        // Nothing of A survives a failed read by B: not the account, not the name, and no save against it.
        assertNull(model.state.value.profile)
        assertEquals("", model.state.value.displayName)
        assertEquals(ProfileViewModel.UNREACHABLE, model.state.value.message)
        assertEquals(false, model.state.value.canSave)
    }

    @Test
    fun `an answer to a read the ended session asked for leaves no account and no name`() {
        // The entry keeps the account only while it believes the session has not changed, so an answer that
        // lands after the session ended would otherwise restore an account and a name that are no longer this
        // operator's. The read is answered with nothing here, which is the case where what the entry left in
        // state would stand for good rather than for the length of a wait.
        val api = StubAuthApi(gateProfile = true)
        val (repository, model) = profileSession(api)

        model.open()
        api.profileGates[0].complete(Unit)
        model.onDisplayNameChange("Ana Souza Silva")

        // A second entry in the same session: the generation has not moved, so the account and the name typed
        // but not sent are both kept, and they are what the answer below must not restore.
        model.open()
        assertEquals("ana", model.state.value.profile?.username)
        assertEquals("Ana Souza Silva", model.state.value.displayName)

        // The session ends while that read is in the air, through the operation a successful password change
        // uses, which is the caller of `forgetSession`.
        repository.forgetSession()

        api.profileAnswer = Response.error(500, errorOnlyBody("Internal error"))
        api.profileGates[1].complete(Unit)

        // Nothing of the ended session survives the answer: not the account, not the name the operator had
        // typed, and not a failure line, which would be a statement about a read whose session is over.
        assertNull(model.state.value.profile)
        assertEquals("", model.state.value.displayName)
        assertNull(model.state.value.message)
        assertEquals(false, model.state.value.loading)
    }

    @Test
    fun `a superseded read does not write over the newer one`() {
        // `open()` really can be called twice with two reads in the air, and the answer that lands last is
        // not necessarily the newest. Without the token the older answer's account is what stays on screen,
        // which is the identity of a read the operator already replaced -- the same leak, one frame later.
        // The gate is what makes that reachable at all: an immediate fake answers before the second entry
        // exists, which is why no test saw it.
        val api = StubAuthApi(gateProfile = true)
        val model = profileViewModel(api)

        model.open()
        model.open()
        assertEquals("one read per entry", 2, api.profileGates.size)

        // The newer read answers first, with the account that is really on screen.
        api.profileAnswer = Response.success(
            ProfileResponse(user = profileUser(username = "bruno", displayName = "Bruno Lima")),
        )
        api.profileGates[1].complete(Unit)
        assertEquals("bruno", model.state.value.profile?.username)
        assertEquals("Bruno Lima", model.state.value.displayName)

        // And the superseded read answers after it, carrying the account it was asked about.
        api.profileAnswer = Response.success(
            ProfileResponse(user = profileUser(username = "ana", displayName = "Ana Souza")),
        )
        api.profileGates[0].complete(Unit)

        // Nothing of the stale answer was written: not the account, not the name, not the loading flag.
        assertEquals("bruno", model.state.value.profile?.username)
        assertEquals("Bruno Lima", model.state.value.displayName)
        assertEquals(false, model.state.value.loading)
    }

    @Test
    fun `a name with no profile loaded behind it is not a save`() {
        // The comparison the gate makes is against the loaded name, so with no profile loaded there is
        // nothing to compare with -- `trim() != null` is true for any name at all. A failed read would
        // otherwise leave the screen offering a save, and sending one, for an account it never read.
        val api = StubAuthApi(profileResponse = Response.error(500, errorOnlyBody("Internal error")))
        val model = profileViewModel(api)
        model.open()
        assertNull(model.state.value.profile)

        model.onDisplayNameChange("Ana S. Souza")

        assertEquals(false, model.state.value.canSave)
        model.save()
        assertEquals("a gate is not a request, and this one has nothing to send", 0, api.updateBodies.size)
    }
}

private fun profileViewModel(
    api: StubAuthApi = StubAuthApi(),
    store: StubTokenStore = StubTokenStore(),
) = profileSession(api, store).second

/**
 * The view model and the repository whose session it reads, for the test that has to *move* the session
 * rather than only look at it.
 *
 * The generation only ever moves through the repository's own operations, so a test that wants a different
 * session has to go through one of them -- and a test that leaves the repository alone is, by construction,
 * still in the same session. That is what the rest of these tests rely on, which is why the helper comes in
 * two shapes instead of the counter ever being handed out.
 */
private fun profileSession(
    api: StubAuthApi = StubAuthApi(),
    store: StubTokenStore = StubTokenStore(),
): Pair<AuthRepository, ProfileViewModel> {
    val repository = AuthRepository(api, store, json())
    return repository to ProfileViewModel(repository)
}

private fun profileUser(username: String = "ana", displayName: String = "Ana Souza") = ProfileResponseUser(
    id = 1,
    username = username,
    displayName = displayName,
    role = "Trabalhador",
)

private fun successBody() = SuccessResponse(success = SuccessResponse.Success.`true`)

/** The same Json the app builds, so a test does not pass against a lenient parser the app lacks. */
private fun json() = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

private fun String.toResponseBody() = this.toResponseBody("application/json".toMediaType())

/**
 * A refusal shaped the way most of this backend's refusals actually are: `error` alone, with no
 * `message` to prefer.
 *
 * This is not a malformed body and must not be mistaken for one. It is the shape of the
 * wrong-current-password refusal, and of every refusal on `auth.routes.ts`.
 */
private fun errorOnlyBody(error: String) = """{"error":"$error"}""".toResponseBody()

/**
 * The auth surface, with only the calls this screen makes implemented.
 *
 * Its own stub rather than the one in `LoginViewModelTest`: that one is private to its file, and it
 * answers the calls a login needs. This one records the requests the profile sends, because most of what
 * these tests are about is what was sent and in which order.
 */
private class StubAuthApi(
    profileResponse: Response<ProfileResponse> =
        Response.success(ProfileResponse(user = profileUser())),
    private val updateResponse: Response<ProfileResponse> =
        Response.success(ProfileResponse(user = profileUser())),
    private val changePasswordResponse: Response<SuccessResponse> = Response.success(successBody()),
    private val failChangePasswordWithTransport: Boolean = false,
    private val onLogout: () -> Unit = {},
    /**
     * When true, every profile read waits on its own gate before answering, so a test can hold two of
     * them in the air at once and choose which one lands last.
     *
     * The gate is released *before* the answer is built, on purpose: the answer is the one that is true
     * when it lands, which is how a test reaches what an immediate fake cannot -- a read that is still
     * unanswered after the entry that made it has already been replaced.
     */
    private val gateProfile: Boolean = false,
) : AuthApi {

    var profileCalls = 0
        private set

    /**
     * The answer the next read builds, writable so a test can change what a held read carries when it
     * lands.
     *
     * A fixed constructor answer cannot serve the two tests the token exists for: the entry that leaks and
     * the entry that supersedes it have to answer with different accounts, and which answer belongs to
     * which read is the whole assertion, so a fake that echoed one account back would measure a flag
     * rather than whose identity is in state.
     */
    var profileAnswer: Response<ProfileResponse> = profileResponse

    /** One gate per profile read held open by [gateProfile], in the order the calls were made. */
    val profileGates = mutableListOf<CompletableDeferred<Unit>>()

    val updateBodies = mutableListOf<UpdateProfileRequest>()
    val changePasswordBodies = mutableListOf<ChangePasswordRequest>()
    val logoutBodies = mutableListOf<LogoutRequest>()

    override suspend fun profile(): Response<ProfileResponse> {
        profileCalls++
        if (gateProfile) {
            val gate = CompletableDeferred<Unit>()
            profileGates += gate
            gate.await()
        }
        return profileAnswer
    }

    override suspend fun updateProfile(body: UpdateProfileRequest): Response<ProfileResponse> {
        updateBodies += body
        return updateResponse
    }

    override suspend fun changePassword(body: ChangePasswordRequest): Response<SuccessResponse> {
        changePasswordBodies += body
        if (failChangePasswordWithTransport) throw java.io.IOException("network down")
        return changePasswordResponse
    }

    override suspend fun logout(body: LogoutRequest): Response<SuccessResponse> {
        logoutBodies += body
        onLogout()
        return Response.success(successBody())
    }

    /**
     * Implemented because a test needs it, which is what the companion note below asks for: a rotation has to
     * really reach the store for the test to be about the generation rather than about a call that answered
     * false before doing anything.
     */
    override suspend fun refresh(body: RefreshRequest): Response<RefreshResponse> =
        Response.success(RefreshResponse(token = "rotated", refreshToken = "rotated-refresh", expiresIn = 900))

    override suspend fun login(body: LoginRequest): Response<LoginResponse> = error(NOT_USED)
    override suspend fun me(): Response<ProfileResponse> = error(NOT_USED)
    override suspend fun setup(body: SetupRequest): Response<SetupResponse> = error(NOT_USED)
    override suspend fun setupStatus(): Response<SetupStatusResponse> = error(NOT_USED)

    private companion object {
        const val NOT_USED = "this stub does not implement that call; add it when a test needs it"
    }
}

/** A store that counts its clears, because "the session was forgotten" is what these tests assert on. */
private class StubTokenStore(private val onClear: () -> Unit = {}) : TokenStore {
    private var access: String? = null
    private var refresh: String? = null

    var clears = 0
        private set

    override fun accessToken(): String? = access
    override fun refreshToken(): String? = refresh

    override fun save(accessToken: String, refreshToken: String) {
        access = accessToken
        refresh = refreshToken
    }

    override fun clear() {
        clears++
        onClear()
        access = null
        refresh = null
    }
}
