package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.contract.models.CreateAdminUserRequest
import com.trindade.app.contract.models.UpdateAdminUserRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns user and worker state, inline form editing, self-protection guards, and server synchronization.
 *
 * Implements permissions and business rules mirroring the backend and SPA AdminDashboard:
 * - [isAdmin]: whether current session role is Administrador.
 * - Access is strictly administrator-only: workers are refused with a Portuguese error message.
 * - Self-protection rules:
 *   - Current signed-in user cannot change their own `role_id` or `is_active`.
 *   - Current signed-in user cannot delete themselves (server guards with 403; UI suppresses delete and toggle).
 * - Validation rules:
 *   - Username: required on creation (min 1 char).
 *   - Display name: required (min 1 char).
 *   - Password: required on creation (min 4 chars); optional on edit (empty = unchanged, if supplied min 4 chars).
 *   - Role: role_id 1 (Administrador) or 2 (Trabalhador). Defaults to 2 (Trabalhador).
 */
@HiltViewModel
class UsersViewModel @Inject constructor(
    private val repository: UsersRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class FormState(
        val userId: Int? = null,
        val username: String = "",
        val displayName: String = "",
        val password: String = "",
        val roleId: Int = 2,
        val isActive: Int = 1,
        val isSelf: Boolean = false,
        val saving: Boolean = false,
    ) {
        val isEditing: Boolean get() = userId != null

        val canSubmit: Boolean
            get() = if (isEditing) {
                displayName.trim().isNotEmpty() &&
                    (password.isEmpty() || password.length >= 4) &&
                    (isSelf || username.trim().isNotEmpty()) &&
                    !saving
            } else {
                username.trim().isNotEmpty() &&
                    displayName.trim().isNotEmpty() &&
                    password.length >= 4 &&
                    (roleId == 1 || roleId == 2) &&
                    !saving
            }
    }

    data class UiState(
        val loading: Boolean = true,
        val users: List<AdminUserResponseUser> = emptyList(),
        val form: FormState? = null,
        val role: String? = null,
        val currentUserId: Int? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val deleteConfirmUser: AdminUserResponseUser? = null,
        val deleting: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN

        fun isSelf(user: AdminUserResponseUser): Boolean =
            currentUserId != null && user.id == currentUserId

        fun canEdit(user: AdminUserResponseUser): Boolean = isAdmin
        fun canToggleActive(user: AdminUserResponseUser): Boolean = isAdmin && !isSelf(user)
        fun canDelete(user: AdminUserResponseUser): Boolean = isAdmin && !isSelf(user)
    }

    private val _uiState = MutableStateFlow(UiState(role = authRepository.sessionRole()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        _uiState.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val role = authRepository.sessionRole()
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        role = role,
                        users = emptyList(),
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }

            val profile = authRepository.profile()
            val currentUserId = profile?.id

            val users = repository.users()
            _uiState.update { current ->
                if (users == null) {
                    current.copy(
                        loading = false,
                        role = role,
                        currentUserId = currentUserId ?: current.currentUserId,
                        message = "Não foi possível carregar os usuários. Tente de novo.",
                        isError = true,
                    )
                } else {
                    current.copy(
                        loading = false,
                        role = role,
                        currentUserId = currentUserId ?: current.currentUserId,
                        users = users,
                        message = null,
                        isError = false,
                    )
                }
            }
        }
    }

    fun openCreateForm() {
        if (!_uiState.value.isAdmin) return
        _uiState.update {
            it.copy(
                form = FormState(roleId = 2, isActive = 1, isSelf = false),
                message = null,
            )
        }
    }

    fun openEditForm(user: AdminUserResponseUser) {
        if (!_uiState.value.canEdit(user)) return
        val isSelf = _uiState.value.isSelf(user)
        _uiState.update {
            it.copy(
                form = FormState(
                    userId = user.id,
                    username = user.username,
                    displayName = user.displayName,
                    password = "",
                    roleId = user.roleId,
                    isActive = user.isActive,
                    isSelf = isSelf,
                ),
                message = null,
            )
        }
    }

    fun closeForm() {
        if (_uiState.value.form?.saving == true) return
        _uiState.update { it.copy(form = null) }
    }

    fun onUsernameChanged(username: String) {
        _uiState.update { current ->
            if (current.form?.isSelf == true) current
            else current.copy(form = current.form?.copy(username = username))
        }
    }

    fun onDisplayNameChanged(displayName: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(displayName = displayName))
        }
    }

    fun onPasswordChanged(password: String) {
        _uiState.update { current ->
            current.copy(form = current.form?.copy(password = password))
        }
    }

    fun onRoleIdChanged(roleId: Int) {
        _uiState.update { current ->
            if (current.form?.isSelf == true) current
            else current.copy(form = current.form?.copy(roleId = roleId))
        }
    }

    fun onIsActiveChanged(isActive: Int) {
        _uiState.update { current ->
            if (current.form?.isSelf == true) current
            else current.copy(form = current.form?.copy(isActive = isActive))
        }
    }

    fun saveUser() {
        val form = _uiState.value.form ?: return
        if (!form.canSubmit || !_uiState.value.isAdmin || authRepository.sessionRole() != RolePolicy.ADMIN) return

        _uiState.update { it.copy(form = form.copy(saving = true), message = null) }

        viewModelScope.launch {
            val role = authRepository.sessionRole()
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        role = role,
                        form = form.copy(saving = false),
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }

            if (form.isEditing) {
                val userId = form.userId ?: return@launch
                val request = if (form.isSelf) {
                    UpdateAdminUserRequest(
                        displayName = form.displayName.trim(),
                        password = form.password.trim().ifEmpty { null },
                    )
                } else {
                    UpdateAdminUserRequest(
                        username = form.username.trim(),
                        displayName = form.displayName.trim(),
                        password = form.password.trim().ifEmpty { null },
                        roleId = if (form.roleId == 1) UpdateAdminUserRequest.RoleId._1 else UpdateAdminUserRequest.RoleId._2,
                        isActive = if (form.isActive == 1) UpdateAdminUserRequest.IsActive._1 else UpdateAdminUserRequest.IsActive._0,
                    )
                }

                when (val result = repository.updateUser(userId, request)) {
                    is UserWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Usuário atualizado com sucesso.", isError = false) }
                        refreshUsers()
                    }
                    is UserWriteResult.Refused -> {
                        val msg = when (result.statusCode) {
                            400 -> "Nome de usuário já existe ou dados inválidos."
                            403 -> if (form.isSelf) {
                                "Não é possível alterar seu próprio perfil de acesso. Atualize apenas seus dados e senha."
                            } else {
                                "Acesso negado: permissão insuficiente."
                            }
                            else -> "Não foi possível salvar o usuário (${result.statusCode})."
                        }
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = msg,
                                isError = true,
                            )
                        }
                    }
                    is UserWriteResult.Unreachable -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Sem conexão com o servidor. Tente de novo.",
                                isError = true,
                            )
                        }
                    }
                }
            } else {
                val request = CreateAdminUserRequest(
                    username = form.username.trim(),
                    displayName = form.displayName.trim(),
                    password = form.password.trim(),
                    roleId = if (form.roleId == 1) CreateAdminUserRequest.RoleId._1 else CreateAdminUserRequest.RoleId._2,
                )

                when (val result = repository.createUser(request)) {
                    is UserWriteResult.Saved -> {
                        _uiState.update { it.copy(form = null, message = "Usuário criado com sucesso.", isError = false) }
                        refreshUsers()
                    }
                    is UserWriteResult.Refused -> {
                        val msg = when (result.statusCode) {
                            400 -> "Nome de usuário já existe ou dados inválidos."
                            403 -> "Acesso negado: permissão insuficiente."
                            else -> "Não foi possível criar o usuário (${result.statusCode})."
                        }
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = msg,
                                isError = true,
                            )
                        }
                    }
                    is UserWriteResult.Unreachable -> {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = "Sem conexão com o servidor. Tente de novo.",
                                isError = true,
                            )
                        }
                    }
                }
            }
        }
    }

    fun toggleUserActive(user: AdminUserResponseUser) {
        if (!_uiState.value.canToggleActive(user) || authRepository.sessionRole() != RolePolicy.ADMIN) return
        val newActive = if (user.isActive == 1) 0 else 1

        viewModelScope.launch {
            val role = authRepository.sessionRole()
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        role = role,
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }
            val request = UpdateAdminUserRequest(
                isActive = if (newActive == 1) UpdateAdminUserRequest.IsActive._1 else UpdateAdminUserRequest.IsActive._0,
            )
            when (val result = repository.updateUser(user.id, request)) {
                is UserWriteResult.Saved -> refreshUsers()
                is UserWriteResult.Refused -> {
                    val msg = when (result.statusCode) {
                        403 -> if (_uiState.value.isSelf(user)) {
                            "Não é possível alterar seu próprio perfil de acesso. Atualize apenas seus dados e senha."
                        } else {
                            "Acesso negado: permissão insuficiente."
                        }
                        else -> "Não foi possível alterar o status do usuário."
                    }
                    _uiState.update { it.copy(message = msg, isError = true) }
                }
                is UserWriteResult.Unreachable -> {
                    _uiState.update { it.copy(message = "Sem conexão com o servidor. Tente de novo.", isError = true) }
                }
            }
        }
    }

    fun requestDelete(user: AdminUserResponseUser) {
        if (!_uiState.value.canDelete(user)) return
        _uiState.update { it.copy(deleteConfirmUser = user, message = null) }
    }

    fun cancelDelete() {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleteConfirmUser = null) }
    }

    fun handleBack(): Boolean {
        if (_uiState.value.deleteConfirmUser != null) {
            cancelDelete()
            return true
        }
        if (_uiState.value.form != null) {
            closeForm()
            return true
        }
        return false
    }

    fun confirmDelete() {
        val user = _uiState.value.deleteConfirmUser ?: return
        if (!_uiState.value.canDelete(user) || authRepository.sessionRole() != RolePolicy.ADMIN) return
        _uiState.update { it.copy(deleting = true) }

        viewModelScope.launch {
            val role = authRepository.sessionRole()
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        role = role,
                        deleteConfirmUser = null,
                        deleting = false,
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }
            when (val result = repository.deleteUser(user.id)) {
                is UserLifecycleResult.Changed -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmUser = null,
                            deleting = false,
                            message = "Usuário excluído com sucesso.",
                            isError = false,
                        )
                    }
                    refreshUsers()
                }
                is UserLifecycleResult.Refused -> {
                    val msg = when (result.statusCode) {
                        403 -> if (_uiState.value.isSelf(user)) {
                            "Não é possível excluir seu próprio usuário. Atualize apenas seus dados e senha."
                        } else {
                            "Acesso negado: permissão insuficiente."
                        }
                        400 -> "Não é possível excluir o usuário pois ele possui registros relacionados."
                        else -> "Não foi possível excluir o usuário (${result.statusCode})."
                    }
                    _uiState.update {
                        it.copy(
                            deleteConfirmUser = null,
                            deleting = false,
                            message = msg,
                            isError = true,
                        )
                    }
                }
                is UserLifecycleResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            deleteConfirmUser = null,
                            deleting = false,
                            message = "Sem conexão com o servidor. Tente de novo.",
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(message = null) }
    }

    private suspend fun refreshUsers() {
        val users = repository.users()
        if (users != null) {
            _uiState.update { it.copy(users = users) }
        }
    }
}
