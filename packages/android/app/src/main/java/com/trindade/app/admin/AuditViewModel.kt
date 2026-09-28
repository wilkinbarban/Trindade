package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.AuditResponseLogsInner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class AuditViewModel @Inject constructor(
    private val repository: AuditRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = false,
        val logs: List<AuditResponseLogsInner> = emptyList(),
        val page: Int = 1,
        val totalPages: Int = 1,
        val total: Int = 0,
        val role: String? = null,
        val error: String? = null,
        val actionInput: String = "",
        val entityTypeInput: String = "",
        val userIdInput: String = "",
        val appliedAction: String? = null,
        val appliedEntityType: String? = null,
        val appliedUserId: Int? = null,
    ) {
        val isAdmin: Boolean get() = !loading && role == ADMIN
        val canGoPrevious: Boolean get() = !loading && isAdmin && page > 1
        val canGoNext: Boolean get() = !loading && isAdmin && page < totalPages
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun load(page: Int = 1) {
        loadJob?.cancel()
        val generation = ++loadGeneration

        if (page < 1) {
            _state.update {
                it.copy(
                    loading = false,
                    role = null,
                    logs = emptyList(),
                    page = 1,
                    totalPages = 1,
                    total = 0,
                    error = null,
                )
            }
            return
        }

        _state.update {
            it.copy(
                loading = true,
                role = null,
                logs = emptyList(),
                page = 1,
                totalPages = 1,
                total = 0,
                error = null,
            )
        }

        loadJob = viewModelScope.launch {
            val profile = authRepository.profile()
            if (generation != loadGeneration) return@launch

            if (profile == null) {
                _state.update { current ->
                    current.copy(
                        loading = false,
                        role = null,
                        logs = emptyList(),
                        page = 1,
                        totalPages = 1,
                        total = 0,
                        error = UNREACHABLE,
                    )
                }
                return@launch
            }

            val role = profile.role
            if (role != ADMIN) {
                _state.update { current ->
                    current.copy(
                        loading = false,
                        role = null,
                        logs = emptyList(),
                        page = 1,
                        totalPages = 1,
                        total = 0,
                        error = null,
                    )
                }
                return@launch
            }

            val currentFilters = _state.value
            val response = repository.audit(
                page = page,
                action = currentFilters.appliedAction,
                entityType = currentFilters.appliedEntityType,
                userId = currentFilters.appliedUserId,
            )
            if (generation != loadGeneration) return@launch

            if (response != null) {
                _state.update { current ->
                    current.copy(
                        loading = false,
                        role = ADMIN,
                        logs = response.logs,
                        page = response.page,
                        totalPages = maxOf(1, response.totalPages),
                        total = response.total,
                        error = null,
                    )
                }
            } else {
                _state.update { current ->
                    current.copy(
                        loading = false,
                        role = ADMIN,
                        logs = emptyList(),
                        page = 1,
                        totalPages = 1,
                        total = 0,
                        error = UNREACHABLE,
                    )
                }
            }
        }
    }

    fun onActionChange(value: String) {
        _state.update { it.copy(actionInput = value) }
    }

    fun onEntityTypeChange(value: String) {
        _state.update { it.copy(entityTypeInput = value) }
    }

    fun onUserIdChange(value: String) {
        _state.update { it.copy(userIdInput = value) }
    }

    fun applyFilters() {
        val current = _state.value
        val trimmedUserId = current.userIdInput.trim()
        val parsedUserId: Int?
        if (trimmedUserId.isNotEmpty()) {
            val parsed = trimmedUserId.toIntOrNull()
            if (parsed == null || parsed <= 0) {
                _state.update { it.copy(error = INVALID_USER_ID) }
                return
            }
            parsedUserId = parsed
        } else {
            parsedUserId = null
        }

        val action = current.actionInput.trim().ifEmpty { null }
        val entityType = current.entityTypeInput.trim().ifEmpty { null }

        _state.update {
            it.copy(
                appliedAction = action,
                appliedEntityType = entityType,
                appliedUserId = parsedUserId,
                error = null,
            )
        }
        load(page = 1)
    }

    fun clearFilters() {
        _state.update {
            it.copy(
                actionInput = "",
                entityTypeInput = "",
                userIdInput = "",
                appliedAction = null,
                appliedEntityType = null,
                appliedUserId = null,
                error = null,
            )
        }
        load(page = 1)
    }

    fun previousPage() {
        val current = _state.value
        if (current.canGoPrevious) {
            load(page = current.page - 1)
        }
    }

    fun nextPage() {
        val current = _state.value
        if (current.canGoNext) {
            load(page = current.page + 1)
        }
    }

    companion object {
        const val ADMIN = "Administrador"
        const val UNREACHABLE = "Could not reach the server. Try again."
        const val INVALID_USER_ID = "User ID must be a positive integer."
    }
}
