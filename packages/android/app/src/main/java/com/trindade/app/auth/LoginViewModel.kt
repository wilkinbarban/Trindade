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
                    LoginResult.Unreachable ->
                        current.copy(submitting = false, message = LoginMessage.Unreachable)
                }
            }
        }
    }
}
