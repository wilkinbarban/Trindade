package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class UsersViewModel @Inject constructor(
    private val repository: UsersRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val users: List<AdminUserResponseUser> = emptyList(),
        val role: String? = null,
        val currentUserId: Int? = null,
        val editingId: Int? = null,
        val username: String = "",
        val displayName: String = "",
        val roleId: Int = DEFAULT_ROLE_ID,
        val isActive: Int = 1,
        val deleteTarget: AdminUserResponseUser? = null,
        val error: String? = null,
        val refusedStatus: Int? = null,
        val hasPasswordWarning: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == ADMIN
        val isEditing: Boolean get() = editingId != null
        val isSelfEditing: Boolean get() = isEditing && editingId == currentUserId
        val isEditingSelf: Boolean get() = isSelfEditing
        val canEditUsername: Boolean get() = !isSelfEditing
        val canChangeUsername: Boolean get() = !isSelfEditing
        val canChangeRole: Boolean get() = !isSelfEditing
        val canChangeStatus: Boolean get() = !isSelfEditing

        val passwordStrengthWarning: Boolean get() = hasPasswordWarning
        val passwordWarning: String? get() = if (hasPasswordWarning) PASSWORD_WARNING else null

        fun canEdit(user: AdminUserResponseUser): Boolean = isAdmin
        fun canToggle(user: AdminUserResponseUser): Boolean = isAdmin && user.id != currentUserId
        fun canDelete(user: AdminUserResponseUser): Boolean = isAdmin && user.id != currentUserId
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val _password = MutableStateFlow("")
    val password: StateFlow<String> = _password.asStateFlow()
    private var reading: Job? = null

    /** Reload on arrival and after writes; cancelling prevents stale responses replacing the newest catalog. */
    fun load() {
        reading?.cancel()
        _state.update { it.copy(loading = true, error = null, refusedStatus = null) }
        reading = viewModelScope.launch {
            val profile = authRepository.profile()
            if (profile == null) {
                _state.update {
                    it.copy(
                        loading = false,
                        role = null,
                        currentUserId = null,
                        error = UNREACHABLE,
                        users = emptyList(),
                    )
                }
                return@launch
            }
            if (profile.role != ADMIN) {
                _state.update {
                    it.copy(
                        loading = false,
                        role = profile.role,
                        currentUserId = profile.id,
                        users = emptyList(),
                        error = null,
                    )
                }
                return@launch
            }
            val users = repository.users()
            if (users == null) {
                _state.update { it.copy(loading = false, error = UNREACHABLE, users = emptyList()) }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    users = users,
                    role = profile.role,
                    currentUserId = profile.id,
                    error = null,
                )
            }
        }
    }

    fun onUsernameChange(value: String) {
        if (_state.value.isSelfEditing) return
        changeForm { copy(username = value) }
    }

    fun onDisplayNameChange(value: String) = changeForm { copy(displayName = value) }

    fun onPasswordChange(value: String) {
        _password.value = value
        _state.update {
            it.copy(
                hasPasswordWarning = isWeakPassword(value),
                error = null,
                refusedStatus = null,
            )
        }
    }

    fun onRoleIdChange(value: Int) {
        if (_state.value.isSelfEditing) return
        changeForm { copy(roleId = value) }
    }

    fun onRoleChange(value: Int) = onRoleIdChange(value)

    fun onIsActiveChange(value: Int) {
        if (_state.value.isSelfEditing) return
        changeForm { copy(isActive = value) }
    }

    fun onStatusChange(value: Int) = onIsActiveChange(value)

    fun edit(user: AdminUserResponseUser) {
        val current = _state.value
        if (current.loading || current.saving || !current.canEdit(user)) return
        _password.value = ""
        _state.update {
            it.copy(
                editingId = user.id,
                username = user.username,
                displayName = user.displayName,
                roleId = user.roleId,
                isActive = user.isActive,
                hasPasswordWarning = false,
                error = null,
                refusedStatus = null,
            )
        }
    }

    fun cancelEdit() {
        _password.value = ""
        _state.update {
            it.copy(
                editingId = null,
                username = "",
                displayName = "",
                roleId = DEFAULT_ROLE_ID,
                isActive = 1,
                hasPasswordWarning = false,
                error = null,
                refusedStatus = null,
            )
        }
    }

    fun requestDelete(user: AdminUserResponseUser) {
        val current = _state.value
        if (current.loading || current.saving || !current.canDelete(user)) return
        _state.update { it.copy(deleteTarget = user, error = null, refusedStatus = null) }
    }

    fun cancelDelete() {
        _state.update { it.copy(deleteTarget = null) }
    }

    fun confirmDelete() {
        val current = _state.value
        if (current.loading || current.saving) return
        val target = current.deleteTarget ?: return
        if (!current.canDelete(target)) return
        _state.update { it.copy(deleteTarget = null) }
        delete(target)
    }

    private fun delete(user: AdminUserResponseUser) {
        val current = _state.value
        if (current.loading || current.saving || !current.canDelete(user)) return
        write { repository.delete(user.id) }
    }

    fun toggle(user: AdminUserResponseUser) {
        val current = _state.value
        if (current.loading || current.saving || !current.canToggle(user)) return
        write {
            repository.update(
                user.id,
                UpdateAdminUserRequest(
                    isActive = if (user.isActive == 1) UpdateAdminUserRequest.IsActive._0 else UpdateAdminUserRequest.IsActive._1,
                ),
            )
        }
    }

    fun save() {
        val current = _state.value
        if (current.loading || current.saving || !current.isAdmin) return

        val username = current.username.trim()
        val displayName = current.displayName.trim()
        val pwd = _password.value

        if (current.editingId == null) {
            if (username.isEmpty()) return validation(USERNAME)
            if (displayName.isEmpty()) return validation(DISPLAY_NAME)
            if (pwd.isEmpty()) return validation(PASSWORD)
            if (pwd.length < MIN_PASSWORD_LENGTH) return validation(PASSWORD_TOO_SHORT)
        } else {
            if (current.isSelfEditing) {
                if (displayName.isEmpty()) return validation(DISPLAY_NAME)
                if (pwd.isNotEmpty() && pwd.length < MIN_PASSWORD_LENGTH) return validation(PASSWORD_TOO_SHORT)
            } else {
                if (username.isEmpty()) return validation(USERNAME)
                if (displayName.isEmpty()) return validation(DISPLAY_NAME)
                if (pwd.isNotEmpty() && pwd.length < MIN_PASSWORD_LENGTH) return validation(PASSWORD_TOO_SHORT)
            }
        }

        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch {
            val result = current.editingId?.let { id ->
                val request = if (current.isSelfEditing) {
                    UpdateAdminUserRequest(
                        displayName = displayName,
                        password = pwd.ifEmpty { null },
                    )
                } else {
                    UpdateAdminUserRequest(
                        username = username,
                        displayName = displayName,
                        password = pwd.ifEmpty { null },
                        roleId = if (current.roleId == 1) UpdateAdminUserRequest.RoleId._1 else UpdateAdminUserRequest.RoleId._2,
                        isActive = if (current.isActive == 1) UpdateAdminUserRequest.IsActive._1 else UpdateAdminUserRequest.IsActive._0,
                    )
                }
                repository.update(id, request)
            } ?: repository.create(
                CreateAdminUserRequest(
                    username = username,
                    displayName = displayName,
                    password = pwd,
                    roleId = if (current.roleId == 1) CreateAdminUserRequest.RoleId._1 else CreateAdminUserRequest.RoleId._2,
                ),
            )
            finishWrite(result)
        }
    }

    private fun write(call: suspend () -> UserWriteResult) {
        if (_state.value.loading || _state.value.saving) return
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch { finishWrite(call()) }
    }

    private fun finishWrite(result: UserWriteResult) {
        when (result) {
            is UserWriteResult.Saved -> {
                _password.value = ""
                _state.update {
                    it.copy(
                        saving = false,
                        editingId = null,
                        username = "",
                        displayName = "",
                        roleId = DEFAULT_ROLE_ID,
                        isActive = 1,
                        deleteTarget = null,
                        hasPasswordWarning = false,
                        error = null,
                        refusedStatus = null,
                    )
                }
                load()
            }
            is UserWriteResult.Refused -> _state.update {
                it.copy(saving = false, error = REFUSED, refusedStatus = result.status)
            }
            UserWriteResult.Unreachable -> _state.update {
                it.copy(saving = false, error = UNREACHABLE, refusedStatus = null)
            }
        }
    }

    private fun isWeakPassword(value: String): Boolean =
        value.isNotEmpty() && !(value.length >= 8 && value.any { it in 'A'..'Z' } && value.any { it in '0'..'9' })

    private fun changeForm(change: UiState.() -> UiState) = _state.update { it.change().copy(error = null, refusedStatus = null) }
    private fun validation(message: String) = _state.update { it.copy(error = message, refusedStatus = null) }

    companion object {
        const val ADMIN = "Administrador"
        const val WORKER = "Trabalhador"
        const val DEFAULT_ROLE_ID = 2
        const val MIN_PASSWORD_LENGTH = 4
        const val USERNAME = "Enter a username."
        const val DISPLAY_NAME = "Enter a display name."
        const val PASSWORD = "Enter a password."
        const val PASSWORD_TOO_SHORT = "Password must be at least 4 characters long."
        const val PASSWORD_WARNING = "Recomendamos uma senha de pelo menos 8 caracteres com letras maiúsculas e números"
        const val REFUSED = "The server refused this user operation."
        const val UNREACHABLE = "Could not reach the server. Try again."
    }
}
