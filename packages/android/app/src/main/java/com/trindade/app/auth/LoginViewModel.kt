package com.trindade.app.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val username: String = "",
        val password: String = "",
        val submitting: Boolean = false,
        val message: LoginMessage? = null,
        val signedIn: Boolean = false,
    ) {
        /**
         * Blank fields are refused here rather than sent, because the server would answer with a
         * validation message and the client already knows the answer. The password is checked for
         * emptiness only: the minimum length is a server rule, and duplicating it here would give the
         * client a second place to be wrong about it.
         */
        val canSubmit: Boolean
            get() = !submitting && username.isNotBlank() && password.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    // Typing clears the previous message: a refusal that stays on screen while its cause is being
    // corrected reads as a stale accusation.
    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, message = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, message = null) }

    fun submit() {
        if (!state.value.canSubmit) return
        _state.update { it.copy(submitting = true, message = null) }

        viewModelScope.launch {
            val result = repository.login(state.value.username.trim(), state.value.password)
            _state.update { current ->
                when (result) {
                    is LoginResult.Success ->
                        // The password is dropped from memory on success; nothing after this needs it.
                        current.copy(submitting = false, signedIn = true, password = "")
                    is LoginResult.Rejected ->
                        current.copy(submitting = false, message = LoginMessage.FromServer(result.message))
                    is LoginResult.Unreachable ->
                        current.copy(submitting = false, message = result.cause.toMessage())
                }
            }
        }
    }

    /**
     * The sentence this app has for a failure that produced no usable answer.
     *
     * "Usable" rather than "no answer at all", because one of the values below is an answer that arrived
     * and could not be read: [UnreachableCause.UnreadableBody] is a reply the client reached and could not
     * parse, so a heading claiming the server never answered would be false about it. Every cause shares
     * the weaker claim this wording makes -- the attempt produced nothing this client could use -- and
     * none of them shares the stronger one.
     *
     * A `when` with no `else` on purpose: a new [UnreachableCause] has to be given an explicit mapping
     * here rather than inheriting whichever one happened to be last. The taxonomy's five values become
     * five of [LoginMessage]'s six cases; the sixth, [LoginMessage.FromServer], is never chosen here,
     * because a refusal that arrived readable comes back as [LoginResult.Rejected] and is rendered from
     * the server's own text.
     *
     * [UnreachableCause.Timeout] maps to [LoginMessage.Timeout] because the server was slow rather than
     * absent. [UnreachableCause.Tls] maps to [LoginMessage.Tls] for a secure transport that failed.
     * [UnreachableCause.UnreadableBody] maps to [LoginMessage.UnreadableBody] for a payload this client
     * could not read. [UnreachableCause.NoRoute] maps to [LoginMessage.Unreachable], the one sentence that
     * is about the network, because a name that did not resolve or a connection that was refused is the
     * one failure a network explains. [UnreachableCause.Unknown] maps to [LoginMessage.Unknown] instead of
     * borrowing that sentence: it is the residue for a Throwable this taxonomy could not name, nothing has
     * shown such a failure to involve a network, and telling the operator to check one would be the same
     * defect this split exists to undo.
     */
    private fun UnreachableCause.toMessage(): LoginMessage = when (this) {
        UnreachableCause.Timeout -> LoginMessage.Timeout
        UnreachableCause.Tls -> LoginMessage.Tls
        UnreachableCause.UnreadableBody -> LoginMessage.UnreadableBody
        UnreachableCause.NoRoute -> LoginMessage.Unreachable
        UnreachableCause.Unknown -> LoginMessage.Unknown
    }

    /**
     * Consumes `signedIn`, to be called once the app has acted on it.
     *
     * A successful sign-in is an event, not a state: it is true only until somebody acts on it, and the
     * thing that acts on it is an effect keyed on the flag, so a value left standing is a report no one
     * is left to hear twice. Reading it is not enough, and the two reasons are worth naming. The event
     * has to be consumed because a flag that only ever goes one way makes the *second* sign-in of one
     * process invisible: `submit()` sets it to the value it already had, the effect's key does not
     * change, the effect never runs again, and the operator is left on the login form holding a live
     * session, with every retry minting another one on the server. And it has to be consumed here rather
     * than assumed away by the caller, because this view model is scoped to the activity's store: it
     * outlives the login screen, so it is still true when the operator signs out and the form comes
     * back. Clearing it makes the next real sign-in a false -> true transition again.
     */
    fun consumeSignIn() {
        _state.update { it.copy(signedIn = false) }
    }
}
