package com.trindade.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.BuildConfig
import com.trindade.app.contract.models.ProfileResponseUser
import com.trindade.app.network.runCatchingCancellable
import com.trindade.app.update.GitHubReleaseApi
import com.trindade.app.update.ReleaseVersion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The account: who is signed in, their display name, and their password.
 *
 * One view model for the one screen, because everything on it is one subject. The two writes are kept
 * apart rather than offered as one "save" the way the SPA's single profile form does: they answer
 * different questions (a name the operator can be wrong about, and a password that ends every session
 * of this user), and a form that sends both on one tap makes a password change happen as a side effect
 * of fixing a name.
 *
 * Nothing here is gated on the role. The endpoint serves both roles and hiding a security action is
 * worse than showing it, which is the case the SPA gets wrong: its profile tab is drawn only for a
 * Trabalhador, so an Administrator cannot change a password from the desktop. This screen is for
 * whoever is signed in.
 *
 * One thing on this screen is not about the account at all: whether a newer release exists. It lives here
 * because the answer is drawn under the version line and because the screen already has an entry point to
 * run it on, and it is kept apart from the account in two directions -- a different source ([GitHubReleaseApi],
 * on a client that carries no token) and a state of its own ([UpdateStatus]) that never touches the error
 * line. See [open] and [refreshUpdateCheck] for why the account and the update are two questions with two
 * answers rather than one result.
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: AuthRepository,
    private val releases: GitHubReleaseApi,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val profile: ProfileResponseUser? = null,
        /** The editable copy of `display_name`, seeded from [profile] and sent as the server's input. */
        val displayName: String = "",
        val saving: Boolean = false,
        val currentPassword: String = "",
        val newPassword: String = "",
        val changing: Boolean = false,
        /**
         * The failure line: a refusal, or a server that could not be reached.
         *
         * Carried as one string because that is what every other screen in this app does with its
         * refusals, and because there is no copy in it this app has to translate: a refusal is the
         * server's own sentence, and the fallbacks below are this app's words for a status code.
         */
        val message: String? = null,
        /**
         * Whether the last save was accepted, which the screen draws in the confirmation tone.
         *
         * Not folded into [message] because the two are not the same event: [message] is something that
         * went wrong and is drawn in the error colour, while this is something that worked. The report
         * generator keeps the same pair apart for the same reason, with its `createdReportId`.
         */
        val nameSaved: Boolean = false,
        /** True once the session has ended, whether by the sign-out action or by a password change. */
        val signedOut: Boolean = false,
        /**
         * What the update check found, drawn under the version line.
         *
         * Its own field rather than a sentence in [message], and that is the decision this field exists to
         * carry: see [UpdateStatus] for the four answers and the screen for why only one of them is
         * allowed to raise its voice.
         */
        val update: UpdateStatus = UpdateStatus.Checking,
    ) {
        /**
         * Whether there is a save worth sending.
         *
         * Blank is refused here rather than at the server, which would answer a validation message the
         * client already knows. A name identical to the loaded one is refused too, and that is the part
         * worth stating: the server writes an audit row for every accepted PATCH, so a save that
         * changes nothing leaves a trace of an edit that did not happen. The comparison is against the
         * trimmed value because that is what would be sent -- whitespace the server would trim away is
         * not a change either.
         *
         * The loaded profile is required as well, and not only as the thing being compared against: with
         * no profile in state there is nothing to compare with, so `trim() != null` would be true for any
         * name at all -- a save offered, and sent, with no account read behind it.
         */
        val canSave: Boolean
            get() = !saving &&
                profile != null &&
                displayName.isNotBlank() &&
                displayName.trim() != profile?.displayName

        /**
         * Whether there is a password change worth sending.
         *
         * The eight-character minimum is the server's rule, stated at the point of typing the way the
         * vehicle rule is stated in the loading form: an operator who learns it from a 400 has typed the
         * password twice and lost the correction. Emptiness is checked instead of a minimum for the
         * current password, which has no length rule of its own.
         */
        val canChangePassword: Boolean
            get() = !changing && currentPassword.isNotEmpty() && newPassword.length >= MIN_PASSWORD_LENGTH
    }

    /**
     * The four answers the update check can leave on the screen.
     *
     * Four, not two, because the two that are not a comparison result are the ones a boolean would have
     * flattened: a check that has not answered yet, and a check that could not be made. The second is not
     * an error about the operator or their session -- the account loaded, the token is fine, GitHub had a
     * bad moment or the tag is not one this client reads -- so it does not belong in [UiState.message],
     * which the screen draws in the error colour and which every other test in this app reads as
     * "something went wrong with the request you made".
     */
    sealed interface UpdateStatus {

        /** In the air: the answer will replace this, or nothing will if the entry is superseded. */
        data object Checking : UpdateStatus

        /** This build is the newest release. Nothing to do, and nothing to say loudly. */
        data object UpToDate : UpdateStatus

        /**
         * A newer release exists, named [version] without the tag's `v`.
         *
         * Carries the number because the sentence it feeds names it, and because the operator's next act
         * is to compare it with the version line directly above.
         */
        data class Available(val version: String) : UpdateStatus

        /** GitHub could not be asked, answered with something unreadable, or named a tag this client reads no version out of. */
        data object CouldNotCheck : UpdateStatus
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Identifies the newest profile read, so an answer to an older one cannot write at all.
     *
     * Two reads really can be in the air at once, because `open()` is callable twice: the previous entry's
     * read is still unanswered when the next entry asks again. Without this, whichever answer lands last
     * wins, and the older one landing last would write the account it was asked about over the entry that
     * replaced it -- the same leak [open] closes, arriving one frame later. The report history, the loading
     * history and the grid all carry this same token for this same reason, so this is that pattern rather
     * than a second one: a counter, taken per request and compared once the answer is in hand.
     */
    private var newestOpen = 0

    /**
     * The session generation the state in hand belongs to, or null while no entry has been made yet.
     *
     * Null rather than a numeric sentinel because there is no number the first entry could honestly call
     * its own: this view model outlives the session, so the state in front of it at that moment may be a
     * leftover from a session that has already ended, and only the comparison in [open] can say which.
     */
    private var lastSeenGeneration: Int? = null

    /**
     * Opens the screen: the profile as the server has it, and nothing left over from before.
     *
     * Called by the route on entry rather than from `init`, and that is about this app's scoping rather
     * than about style: view models are scoped to the activity here, so this one outlives the screen.
     * After a password change the signed-out panel would otherwise still be in place the next time
     * anybody opened the profile -- in a following session, under a different user -- and a refusal
     * would sit under a form nobody had touched. Opening is the moment all of that stops being true,
     * so opening is where it is dropped.
     *
     * The account goes with the rest when the session has changed, and that is the part scoping makes
     * load-bearing rather than tidy. This view model outlives the session as well as the screen, so the
     * profile and the editable name in state may both be the previous operator's -- on a shared phone, A's,
     * read by B. Left in place, they are A's username, display name and role for the whole of B's wait, they
     * are still A's if B's read fails, and [UiState.canSave] would be live against A's name. The day the
     * session ended is A's last moment on this screen, so the entry that follows is the moment the account
     * leaves it.
     *
     * **Which entry that is, though, is the question [AuthRepository.sessionGeneration] answers**, and asking
     * it is what keeps the clear from being a cure worse than the leak. An entry in the **same** session is
     * the same operator asking for their own account again: dropping it there would blank the screen they are
     * still looking at for the length of a read, take a name they had typed but not yet saved with it, and --
     * since the failure path below keeps what the entry left it -- turn a network fault into an empty account
     * block where the account that had been read belongs. So the clear is conditional on the session having
     * changed, and only on that. The generation is read once, before the state is touched, so the entry and
     * the answer it starts cannot disagree about which session they belong to.
     */
    fun open() {
        val open = ++newestOpen
        val generation = repository.sessionGeneration
        val sameSession = generation == lastSeenGeneration
        lastSeenGeneration = generation

        _state.update {
            it.copy(
                loading = true,
                // The previous operator's account: dropped unless this entry is the same operator entering
                // again, in which case what is here is their own. A read that is in flight or that never
                // arrives must not be able to present one operator's copy as another's.
                profile = if (sameSession) it.profile else null,
                displayName = if (sameSession) it.displayName else "",
                message = null,
                nameSaved = false,
                signedOut = false,
                // The password fields go with the rest: they were typed for a conversion that is over.
                currentPassword = "",
                newPassword = "",
                // This entry runs its own check, so what was in state was the previous entry's answer and
                // not this one's. Reset unconditionally, unlike the account above: the update state says
                // nothing about who is signed in, so there is no session question to ask about it -- every
                // entry has to ask GitHub again, whoever is entering.
                update = UpdateStatus.Checking,
            )
        }

        viewModelScope.launch {
            val profile = repository.profile()

            // A superseded answer is dropped whole: not the account, not the name, not the message. It
            // describes a read the operator has already replaced, and writing it would put back exactly
            // the identity the entry above took away. This check stays **first**, ahead of the session one
            // below, because that one writes: an answer this read has already been replaced by must not
            // reach state at all, and an older read of an ended session landing after a newer one had
            // answered for the entry that replaced it would otherwise wipe that newer answer out.
            if (open != newestOpen) return@launch

            // The other question an answer has to pass, and not the same one: not "am I the newest read"
            // but "is the session that asked me still the one in hand". [generation] above is the one this
            // read was issued for, and the entry that issued it kept the account in state only because it
            // believed the session had not changed. Once it has, that belief is false, and the account and
            // the name it retained are a foreign operator's: they go with this answer rather than staying
            // on until somebody opens the screen again. So nothing of the answer is written either -- not
            // the account it carried, not the name, and not the restore the failure path below would
            // otherwise perform with the profile the entry left behind.
            if (repository.sessionGeneration != generation) {
                _state.update { it.copy(loading = false, profile = null, displayName = "") }
                return@launch
            }

            _state.update {
                it.copy(
                    loading = false,
                    // Written when an account arrived, and when none did the state keeps what it has: nothing
                    // for an entry that changed session, because that entry emptied it above, and the same
                    // operator's own account for an entry that did not. A read that fails is not evidence
                    // about a session that did not change, so it must not be read as one -- that is this half
                    // of the rule, and the half that keeps the identity away from the next operator is the
                    // entry above, which decides whether there was somebody else's account here at all.
                    profile = profile ?: it.profile,
                    // Seeded from the server, and left as it was when nothing arrived: there is no name
                    // to seed with, and blanking a form somebody may be reading is not an answer to a
                    // network fault. That reasoning is the same-session reload's -- a read that fails while
                    // the same operator is on the screen -- and what it keeps is only ever theirs: the entry
                    // above has already dropped the previous account's copy, so there is none of somebody
                    // else's here for this line to leave standing.
                    displayName = profile?.displayName ?: it.displayName,
                    message = if (profile == null) UNREACHABLE else null,
                )
            }
        }

        // Started here rather than after the read above, and not folded into that coroutine: the two are
        // separate questions with separate answers, so a slow or failing account read must not hold the
        // update line back or take it down with it. What they share is the token.
        viewModelScope.launch {
            val update = refreshUpdateCheck()

            // The same token, for the same reason and with the same rule: two entries really can have two
            // checks in the air at once, the answer that lands last is not necessarily the newest, and a
            // superseded answer writes nothing at all.
            //
            // Only the token, though -- the session check in the read above deliberately has no twin here.
            // That one exists because a profile answer carries an operator's account and a session can end
            // while it flies, so writing it would present one person's identity to the next. This answer
            // carries a version number, which is the same fact about the app for whoever is signed in, so a
            // session change leaves nothing of anybody's in it to leak.
            if (open != newestOpen) return@launch

            _state.update { it.copy(update = update) }
        }
    }

    /**
     * Asks GitHub which release is newest, and compares it with the version this build carries.
     *
     * The call goes through [runCatchingCancellable] for the reason every repository in this app does: a
     * transport failure, a refusal and a payload that does not decode are all the same answer here --
     * "could not check" -- while a cancellation has to leave the coroutine rather than be reported as one,
     * which is the half a plain `runCatching` gets wrong. The decode failure is worth naming because it is
     * reachable: [GitHubRelease] requires `tag_name`, so a body without it throws inside the call instead of
     * answering null -- and without this wrapper that throw would leave the coroutine and take the screen
     * with it, so the wrapper is load-bearing here rather than only tidy.
     *
     * The endpoint's filters are not repeated here. GitHub's `releases/latest` already excludes drafts and
     * prereleases, so a prerelease tag cannot arrive; and a tag this build's own comparison cannot read is
     * [ReleaseVersion.Comparison.Undetermined], which is "could not check" on the screen rather than a
     * guess dressed as an answer.
     */
    private suspend fun refreshUpdateCheck(): UpdateStatus {
        val release = runCatchingCancellable { releases.latestRelease() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?: return UpdateStatus.CouldNotCheck

        return when (val comparison = ReleaseVersion.compare(release.tagName, BuildConfig.APP_VERSION_NAME)) {
            is ReleaseVersion.Comparison.Newer -> UpdateStatus.Available(comparison.version)
            ReleaseVersion.Comparison.UpToDate -> UpdateStatus.UpToDate
            ReleaseVersion.Comparison.Undetermined -> UpdateStatus.CouldNotCheck
        }
    }

    // Typing clears the previous line, the way the login form does: a refusal that stays on screen
    // while its cause is being corrected reads as a stale accusation, and a confirmation outlives its
    // moment the same way.
    fun onDisplayNameChange(value: String) =
        _state.update { it.copy(displayName = value, message = null, nameSaved = false) }

    fun onCurrentPasswordChange(value: String) = _state.update { it.copy(currentPassword = value, message = null) }

    fun onNewPasswordChange(value: String) = _state.update { it.copy(newPassword = value, message = null) }

    /** Saves the display name. See [UiState.canSave] for what is not sent. */
    fun save() {
        val current = state.value
        if (!current.canSave) return
        _state.update { it.copy(saving = true, message = null, nameSaved = false) }

        viewModelScope.launch {
            when (val result = repository.saveDisplayName(current.displayName.trim())) {
                is ProfileUpdateResult.Saved -> _state.update {
                    // The PATCH answers the stored row, so that user is the new truth and there is
                    // nothing left to read: a refetch could only repeat it or contradict it.
                    it.copy(
                        saving = false,
                        profile = result.user,
                        displayName = result.user.displayName,
                        nameSaved = true,
                    )
                }

                is ProfileUpdateResult.Refused -> _state.update {
                    // The operator's text is kept. A refusal is theirs to resolve -- this name is not one
                    // the server accepts -- and clearing the field would discard their work in the act
                    // of telling them about it. That is the lesson D4c recorded for the quick-add form.
                    it.copy(saving = false, message = saveRefusal(result.statusCode))
                }

                ProfileUpdateResult.Unreachable ->
                    _state.update { it.copy(saving = false, message = UNREACHABLE) }
            }
        }
    }

    /**
     * Changes the password, and ends the session when it works.
     *
     * The sign-out is not a courtesy: the server revokes **every** live session of this user as part of
     * a successful change, this one included, and access tokens are stateless fifteen-minute JWTs. Keeping
     * the session would buy at most a quarter of an hour before the first 401 tried to refresh a session
     * that no longer exists. So the local session is dropped here, and the operator is told why rather
     * than dropped at a login screen with no explanation.
     */
    fun changePassword() {
        val current = state.value
        if (!current.canChangePassword) return
        _state.update { it.copy(changing = true, message = null, nameSaved = false) }

        viewModelScope.launch {
            when (val result = repository.changePassword(current.currentPassword, current.newPassword)) {
                PasswordChangeResult.Changed -> {
                    repository.forgetSession()
                    _state.update {
                        it.copy(
                            changing = false,
                            // Both passwords leave the state as soon as they are not needed. The login
                            // form does the same for the same reason: a password held in state is a
                            // password held in a heap dump.
                            currentPassword = "",
                            newPassword = "",
                            signedOut = true,
                            message = PASSWORD_CHANGED,
                        )
                    }
                }

                is PasswordChangeResult.Rejected -> _state.update {
                    // The server's own sentence, and nothing of this app's on top of it. A wrong current
                    // password answers `{ error: 'Invalid current password' }` and that sentence is the
                    // whole of what the operator has to act on. Both fields are kept: the one that was
                    // refused is theirs to correct.
                    it.copy(changing = false, message = result.message)
                }

                PasswordChangeResult.Unreachable -> _state.update {
                    // Not signed out: nothing was changed, so the session is still real and sending the
                    // operator back to the login would misdescribe what happened.
                    it.copy(changing = false, message = UNREACHABLE)
                }
            }
        }
    }

    /**
     * Ends the session: this device's refresh session is revoked on the server, and then the session is
     * forgotten locally.
     *
     * The two happen in that order, which is [AuthRepository.logout]'s own order and the reason it is
     * that way round: the logout route is authenticated, and the access token comes from the store, so a
     * store cleared first would send an unauthenticated call that revokes nothing. [signedOut] is only
     * raised once that call has settled, because the activity reads the store as it leaves this screen --
     * a navigation that outran the clear would send the operator back into the app.
     */
    fun signOut() {
        if (state.value.signedOut) return
        _state.update { it.copy(nameSaved = false) }

        viewModelScope.launch {
            repository.logout()
            _state.update { it.copy(signedOut = true, message = null) }
        }
    }

    /**
     * What to say about a refused save.
     *
     * 400 is the schema refusing the body, which for this call means a name it will not accept; anything
     * else is a status with no cause this client can name, and naming one would be inventing it. Both are
     * fallbacks: the server usually sends its own sentence and that one is shown instead.
     */
    private fun saveRefusal(statusCode: Int): String = when (statusCode) {
        400 -> CHECK_NAME
        else -> GENERIC
    }

    companion object {
        /**
         * The new password's minimum, which is the server's rule rather than a preference.
         *
         * Public because the screen states it next to the field the operator types in, and both sides
         * have to be the same number: a form that says eight and a server that wants another is a rule
         * learned from a refusal.
         */
        const val MIN_PASSWORD_LENGTH = 8

        const val UNREACHABLE = "Sem conexão com o servidor. Verifique a rede e tente de novo."
        const val PASSWORD_CHANGED = "Senha alterada. Entre de novo."
        private const val GENERIC = "Não foi possível concluir. Tente de novo."
        private const val CHECK_NAME = "Confira o nome e tente de novo."
    }
}
