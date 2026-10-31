package com.trindade.app.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.admin.UsersRepository
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.admin.UserWriteResult
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.ui.filterPhoneDigits
import com.trindade.app.ui.toWhatsAppNumber
import com.trindade.app.ui.waMeLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class ShareAppViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val usersRepository: UsersRepository,
) : ViewModel() {

    data class UiState(
        val platform: SharePlatform = SharePlatform.ANDROID,
        val release: ShareReleaseInfo = ShareReleaseInfo(),
        val users: List<AdminUserResponseUser> = emptyList(),
        val selectedUserId: Int? = null,
        val displayName: String = "",
        val username: String = "",
        val password: String = "123456789",
        val includeCredentials: Boolean = true,
        /** Creation is the default: the admin already knows the person's name, and leaving to the admin panel is
         * where the sharing stops happening. */
        val credentialMode: ShareCredentialMode = ShareCredentialMode.CREATE,
        val quickName: String = "",
        val quickUsername: String = "",
        /** The login is derived from the name until the admin types over it, and never after. */
        val usernameEdited: Boolean = false,
        /** The login whose account was already created in this visit, so sharing twice does not create twice. */
        val createdUsername: String? = null,
        val creating: Boolean = false,
        val createError: ShareCreateError? = null,
        val phone: String = "",
        val isAdmin: Boolean = false,
        val note: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val userRole = authRepository.sessionRole()
        val admin = userRole == RolePolicy.ADMIN
        _state.update { it.copy(isAdmin = admin) }
        if (admin) {
            loadUsers()
        }
    }

    fun loadUsers() {
        viewModelScope.launch {
            val list = usersRepository.users()
            if (list != null) {
                _state.update { it.copy(users = list) }
            }
        }
    }

    fun onPlatformSelect(platform: SharePlatform) {
        _state.update { it.copy(platform = platform) }
    }

    fun onUserSelect(userId: Int?) {
        if (userId == null) {
            _state.update { it.copy(selectedUserId = null, displayName = "", username = "") }
            return
        }
        val found = _state.value.users.find { it.id == userId }
        if (found != null) {
            _state.update {
                it.copy(
                    selectedUserId = userId,
                    displayName = found.displayName,
                    username = found.username,
                )
            }
        }
    }

    fun onPasswordChange(newPass: String) {
        _state.update { it.copy(password = newPass) }
    }

    fun onIncludeCredentialsToggle(enabled: Boolean) {
        _state.update { it.copy(includeCredentials = enabled) }
    }

    fun onCredentialModeSelect(mode: ShareCredentialMode) {
        _state.update { it.copy(credentialMode = mode, createError = null) }
    }

    fun onQuickNameChange(value: String) {
        _state.update { state ->
            state.copy(
                quickName = value,
                createError = null,
                quickUsername = if (state.usernameEdited) state.quickUsername else deriveUsername(value),
            )
        }
    }

    /** The admin's own login wins over the derivation from here on, even if the name changes again. */
    fun onQuickUsernameChange(value: String) {
        _state.update { it.copy(quickUsername = deriveUsername(value), usernameEdited = true, createError = null) }
    }

    /** The login the message and the account use: what the admin typed, or what the name suggests. */
    fun quickLogin(): String = _state.value.quickUsername.ifBlank { deriveUsername(_state.value.quickName) }

    fun quickPassword(): String = quickWorkerPassword(quickLogin())

    /**
     * Creates the worker the message is about, once per login.
     *
     * Sharing credentials for an account that does not exist yet sends the operator to a login screen that
     * refuses them, so the account is created before anything leaves the phone. The role is fixed: this screen
     * hands out workers, and a picker here would be a quiet way to grant an admin.
     */
    suspend fun ensureWorkerCreated(): Boolean {
        val s = _state.value
        if (!s.isAdmin || !s.includeCredentials || s.credentialMode != ShareCredentialMode.CREATE) return true
        val displayName = s.quickName.trim()
        val username = quickLogin().trim().lowercase()
        if (displayName.isEmpty()) {
            _state.update { it.copy(createError = ShareCreateError.NAME_REQUIRED) }
            return false
        }
        if (username.isEmpty()) {
            _state.update { it.copy(createError = ShareCreateError.USERNAME_REQUIRED) }
            return false
        }
        if (s.createdUsername == username) return true

        _state.update { it.copy(creating = true, createError = null) }
        val result = usersRepository.create(
            CreateAdminUserRequest(
                username = username,
                password = quickWorkerPassword(username),
                displayName = displayName,
                roleId = CreateAdminUserRequest.RoleId._2,
            ),
        )
        return when (result) {
            is UserWriteResult.Saved -> {
                _state.update { it.copy(creating = false, createdUsername = username, createError = null) }
                loadUsers()
                true
            }
            is UserWriteResult.Refused -> {
                _state.update {
                    it.copy(
                        creating = false,
                        createError = if (result.status == 409) ShareCreateError.DUPLICATED else ShareCreateError.REFUSED,
                    )
                }
                false
            }
            UserWriteResult.Unreachable -> {
                _state.update { it.copy(creating = false, createError = ShareCreateError.UNREACHABLE) }
                false
            }
        }
    }

    fun onPhoneChange(raw: String) {
        _state.update { it.copy(phone = filterPhoneDigits(raw)) }
    }

    fun onNote(text: String) {
        _state.update { it.copy(note = text) }
    }

    fun message(): String {
        val s = _state.value
        if (s.isAdmin && s.includeCredentials && s.credentialMode == ShareCredentialMode.CREATE) {
            val login = quickLogin()
            val creds = if (login.isBlank()) null else ShareCredentials(
                enabled = true,
                displayName = s.quickName.ifBlank { null },
                username = login,
                password = quickWorkerPassword(login),
            )
            return buildShareMessage(
                ShareMessageParams(
                    platform = s.platform,
                    release = s.release,
                    credentials = creds,
                    locale = Locale.getDefault().language,
                ),
            )
        }
        val creds = if (s.isAdmin && s.includeCredentials && s.username.isNotBlank()) {
            ShareCredentials(
                enabled = true,
                displayName = s.displayName.ifBlank { null },
                username = s.username.ifBlank { null },
                password = s.password.ifBlank { "123456789" },
            )
        } else null

        val locale = Locale.getDefault().language
        return buildShareMessage(
            ShareMessageParams(
                platform = s.platform,
                release = s.release,
                credentials = creds,
                locale = locale,
            ),
        )
    }

    fun whatsappLink(): String? {
        val number = toWhatsAppNumber(_state.value.phone)
        if (number.isEmpty()) return null
        return waMeLink(number, message())
    }
}
