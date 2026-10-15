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

/**
 * Message shown to the operator after a registration failure or validation defect.
 */
sealed interface RegisterMessage {
    /** The server's own words or validation details. */
    data class FromServer(val text: String) : RegisterMessage

    /** Rate limiting with optional retry duration in seconds. */
    data class RateLimited(val text: String, val retryAfterSeconds: Int? = null) : RegisterMessage

    /** Client-side validation message (mismatching passwords, minimum length). */
    data class Validation(val text: String) : RegisterMessage

    data object Timeout : RegisterMessage
    data object Tls : RegisterMessage
    data object UnreadableBody : RegisterMessage
    data object Unreachable : RegisterMessage
    data object Unknown : RegisterMessage
}

@HiltViewModel
class RegisterViewModel @Inject constructor(
    private val repository: AuthRepository,
) : ViewModel() {

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
            get() = !submitting &&
                successMessage == null &&
                username.isNotBlank() &&
                displayName.isNotBlank() &&
                password.isNotBlank() &&
                confirmPassword.isNotBlank()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun onUsernameChange(value: String) = _state.update { it.copy(username = value, message = null) }

    fun onDisplayNameChange(value: String) = _state.update { it.copy(displayName = value, message = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, message = null) }

    fun onConfirmPasswordChange(value: String) = _state.update { it.copy(confirmPassword = value, message = null) }

    fun submit() {
        val current = state.value
        if (!current.canSubmit) return

        // The lengths are measured on the trimmed values, because that is what the request carries and
        // what the server bounds: `registerSchema` applies `.trim()` before `.max(50)`/`.max(100)`, so a
        // username that is only over the limit through surrounding spaces is one the server would accept.
        val username = current.username.trim()
        val displayName = current.displayName.trim()

        if (username.length > USERNAME_MAX_LENGTH) {
            rejectValidation(USERNAME_TOO_LONG)
            return
        }

        if (displayName.length > DISPLAY_NAME_MAX_LENGTH) {
            rejectValidation(DISPLAY_NAME_TOO_LONG)
            return
        }

        if (current.password != current.confirmPassword) {
            rejectValidation(PASSWORD_MISMATCH)
            return
        }

        if (current.password.length < PASSWORD_MIN_LENGTH) {
            rejectValidation(PASSWORD_TOO_SHORT)
            return
        }

        // Two limits, because the backend has two and they are not the same limit. The character ceiling is
        // what `bcrypt` refuses above; the byte ceiling is the one a password made of accented or other
        // multi-byte characters can cross while staying under 72 of them (25 `€` is 25 characters and 75
        // bytes). Checking only the count would let that password reach the server to be refused there.
        if (current.password.length > PASSWORD_MAX_LENGTH) {
            rejectValidation(PASSWORD_TOO_LONG)
            return
        }

        if (current.password.toByteArray(Charsets.UTF_8).size > PASSWORD_MAX_BYTES) {
            rejectValidation(PASSWORD_TOO_MANY_BYTES)
            return
        }

        _state.update { it.copy(submitting = true, message = null) }

        viewModelScope.launch {
            val result = repository.register(
                username = current.username.trim(),
                displayName = current.displayName.trim(),
                password = current.password,
            )
            _state.update { prev ->
                when (result) {
                    is RegisterResult.Success ->
                        // Passwords dropped immediately from memory upon success
                        prev.copy(
                            submitting = false,
                            successMessage = result.message,
                            password = "",
                            confirmPassword = "",
                        )
                    is RegisterResult.Rejected -> {
                        val msg = if (result.statusCode == 429) {
                            RegisterMessage.RateLimited(result.message, result.retryAfterSeconds)
                        } else {
                            RegisterMessage.FromServer(result.message)
                        }
                        prev.copy(submitting = false, message = msg)
                    }
                    is RegisterResult.Unreachable ->
                        prev.copy(submitting = false, message = result.cause.toMessage())
                }
            }
        }
    }

    private fun UnreachableCause.toMessage(): RegisterMessage = when (this) {
        UnreachableCause.Timeout -> RegisterMessage.Timeout
        UnreachableCause.Tls -> RegisterMessage.Tls
        UnreachableCause.UnreadableBody -> RegisterMessage.UnreadableBody
        UnreachableCause.NoRoute -> RegisterMessage.Unreachable
        UnreachableCause.Unknown -> RegisterMessage.Unknown
    }

    fun reset() {
        _state.value = UiState()
    }

    /**
     * Refuses the form with a client-side sentence, before any request is built.
     *
     * Every validation above leaves the same way: a [RegisterMessage.Validation] on the state and no
     * submission. The caller returns immediately after, because a rejected form must not also be sent.
     */
    private fun rejectValidation(text: String) {
        _state.update { it.copy(message = RegisterMessage.Validation(text)) }
    }

    companion object {
        /**
         * The same bounds `registerSchema` in `auth.schema.ts` enforces, so the operator is told which
         * field is wrong instead of meeting the server's refusal as a 400.
         *
         * The numbers are mirrored rather than imported, because the backend is another package, and the
         * mirror is exact: zod's `.max()` counts the same UTF-16 units [String.length] does, `Buffer.byteLength`
         * is the UTF-8 length read below, and `registerSchema` trims `username` and `display_name` before
         * bounding them, which is why [submit] measures the trimmed values.
         */
        const val USERNAME_MAX_LENGTH = 50
        const val DISPLAY_NAME_MAX_LENGTH = 100
        const val PASSWORD_MIN_LENGTH = 8
        const val PASSWORD_MAX_LENGTH = 72
        const val PASSWORD_MAX_BYTES = 72

        // Portuguese, like every sentence this screen owns: the app's own copy is the one text the server
        // did not write. `strings.xml` carries the same four lines for the manual reader; they cannot be
        // fetched from here, because a view model has no `Context`.
        const val USERNAME_TOO_LONG = "O nome de usuário deve ter no máximo 50 caracteres."
        const val DISPLAY_NAME_TOO_LONG = "O nome de exibição deve ter no máximo 100 caracteres."
        const val PASSWORD_MISMATCH = "As senhas não coincidem."
        const val PASSWORD_TOO_SHORT = "A senha deve ter pelo menos 8 caracteres."
        const val PASSWORD_TOO_LONG = "A senha deve ter no máximo 72 caracteres."
        const val PASSWORD_TOO_MANY_BYTES = "A senha deve ter no máximo 72 bytes."
    }
}
