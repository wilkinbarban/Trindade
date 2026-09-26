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

/** Presentation contract for enrollment errors; detailed mappings are covered in R2c. */
sealed interface RegisterMessage {
    data class Validation(val text: String) : RegisterMessage
    data class FromServer(val text: String) : RegisterMessage
    data class RateLimited(val text: String, val retryAfterSeconds: Int? = null) : RegisterMessage
    data object Timeout : RegisterMessage
    data object Tls : RegisterMessage
    data object UnreadableBody : RegisterMessage
    data object Unreachable : RegisterMessage
    data object Unknown : RegisterMessage
}

@HiltViewModel
class RegisterViewModel @Inject constructor(private val repository: AuthRepository) : ViewModel() {
    data class UiState(
        val username: String = "",
        val displayName: String = "",
        val password: String = "",
        val confirmPassword: String = "",
        val submitting: Boolean = false,
        val message: RegisterMessage? = null,
        val successMessage: String? = null,
    ) {
        val canSubmit: Boolean
            get() = !submitting && successMessage == null && username.isNotBlank() &&
                displayName.isNotBlank() && password.isNotBlank() && confirmPassword.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, message = null) }
    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value, message = null) }
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, message = null) }
    fun onConfirmPasswordChange(value: String) = _state.update { it.copy(confirmPassword = value, message = null) }

    private var generation = 0

    fun reset() {
        generation++
        _state.value = UiState()
    }

    fun submit() {
        val form = state.value
        if (!form.canSubmit) return
        val username = form.username.trim()
        val displayName = form.displayName.trim()
        val validation = when {
            username.length > USERNAME_MAX_LENGTH -> USERNAME_TOO_LONG
            displayName.length > DISPLAY_NAME_MAX_LENGTH -> DISPLAY_NAME_TOO_LONG
            form.password != form.confirmPassword -> PASSWORD_MISMATCH
            form.password.length < PASSWORD_MIN_LENGTH -> PASSWORD_TOO_SHORT
            form.password.length > PASSWORD_MAX_LENGTH -> PASSWORD_TOO_LONG
            form.password.toByteArray(Charsets.UTF_8).size > PASSWORD_MAX_BYTES -> PASSWORD_TOO_MANY_BYTES
            else -> null
        }
        if (validation != null) {
            _state.update { it.copy(message = RegisterMessage.Validation(validation)) }
            return
        }
        _state.update { it.copy(submitting = true, message = null) }
        val requestGeneration = generation
        viewModelScope.launch {
            val result = repository.register(username, displayName, form.password)
            _state.update { previous ->
                if (requestGeneration != generation) return@update previous
                when (result) {
                    is RegisterResult.Success -> previous.copy(
                        submitting = false, successMessage = result.message,
                        password = "", confirmPassword = "",
                    )
                    is RegisterResult.Rejected -> previous.copy(
                        submitting = false,
                        message = if (result.statusCode == 429) {
                            RegisterMessage.RateLimited(result.message, result.retryAfterSeconds)
                        } else RegisterMessage.FromServer(result.message),
                    )
                    is RegisterResult.Unreachable -> previous.copy(
                        submitting = false,
                        message = when (result.cause) {
                            UnreachableCause.Timeout -> RegisterMessage.Timeout
                            UnreachableCause.Tls -> RegisterMessage.Tls
                            UnreachableCause.UnreadableBody -> RegisterMessage.UnreadableBody
                            UnreachableCause.NoRoute -> RegisterMessage.Unreachable
                            UnreachableCause.Unknown -> RegisterMessage.Unknown
                        },
                    )
                }
            }
        }
    }

    companion object {
        const val USERNAME_MAX_LENGTH = 50
        const val DISPLAY_NAME_MAX_LENGTH = 100
        const val PASSWORD_MIN_LENGTH = 8
        const val PASSWORD_MAX_LENGTH = 72
        const val PASSWORD_MAX_BYTES = 72
        const val USERNAME_TOO_LONG = "O nome de usuário deve ter no máximo 50 caracteres."
        const val DISPLAY_NAME_TOO_LONG = "O nome de exibição deve ter no máximo 100 caracteres."
        const val PASSWORD_MISMATCH = "As senhas não coincidem."
        const val PASSWORD_TOO_SHORT = "A senha deve ter pelo menos 8 caracteres."
        const val PASSWORD_TOO_LONG = "A senha deve ter no máximo 72 caracteres."
        const val PASSWORD_TOO_MANY_BYTES = "A senha deve ter no máximo 72 bytes."
    }
}
