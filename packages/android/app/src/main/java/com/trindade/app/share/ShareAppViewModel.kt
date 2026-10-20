package com.trindade.app.share

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.admin.UsersRepository
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.ui.formatPhoneMask
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

    fun onPhoneChange(raw: String) {
        _state.update { it.copy(phone = formatPhoneMask(raw)) }
    }

    fun onNote(text: String) {
        _state.update { it.copy(note = text) }
    }

    fun message(): String {
        val s = _state.value
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
