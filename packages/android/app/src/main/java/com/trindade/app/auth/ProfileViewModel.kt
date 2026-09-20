package com.trindade.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.ProfileResponseUser
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
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val repository: AuthRepository,
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
     * Opens the screen: the profile as the server has it, and nothing left over from before.
     *
     * Called by the route on entry rather than from `init`, and that is about this app's scoping rather
     * than about style: view models are scoped to the activity here, so this one outlives the screen.
     * After a password change the signed-out panel would otherwise still be in place the next time
     * anybody opened the profile -- in a following session, under a different user -- and a refusal
     * would sit under a form nobody had touched. Opening is the moment all of that stops being true,
     * so opening is where it is dropped.
     *
     * The account goes with the rest, and that is the part scoping makes load-bearing rather than tidy.
     * This view model outlives the session as well as the screen, so as this entry begins, the profile and
     * the editable name in state may both be the previous operator's -- on a shared phone, A's, read by B.
     * Left in place, they are A's username, display name and role for the whole of B's wait, they are still
     * A's if B's read fails (the failure path deliberately keeps a name it cannot replace), and [UiState.canSave]
     * would be live against A's name. The day the session ended is A's last moment on this screen, so the
     * entry that follows is the moment the account leaves it.
     */
    fun open() {
        val open = ++newestOpen
        _state.update {
            it.copy(
                loading = true,
                // The previous operator's account: for a new entry there is nobody whose copy this could be,
                // and a read that is in flight or that never arrives must not be able to present it as this
                // operator's own.
                profile = null,
                displayName = "",
                message = null,
                nameSaved = false,
                signedOut = false,
                // The password fields go with the rest: they were typed for a conversion that is over.
                currentPassword = "",
                newPassword = "",
            )
        }

        viewModelScope.launch {
            val profile = repository.profile()

            // A superseded answer is dropped whole: not the account, not the name, not the message. It
            // describes a read the operator has already replaced, and writing it would put back exactly
            // the identity the entry above took away.
            if (open != newestOpen) return@launch

            _state.update {
                it.copy(
                    loading = false,
                    profile = profile,
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
