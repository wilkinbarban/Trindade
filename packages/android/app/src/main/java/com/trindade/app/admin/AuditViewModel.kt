package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.contract.models.AuditResponseLogsInner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns system audit log state, web-equivalent filtering, pagination, and access control.
 *
 * Implements permissions and business rules mirroring the backend and SPA AuditPage:
 * - Strictly administrator-only: workers are refused with a Portuguese error message and never query the server.
 * - Server 403 role refusals are caught and displayed as authorization errors.
 * - Supports filtering by action, entity type, and user ID.
 * - Manages pagination (page, limit=20, total, totalPages) with previous and next actions.
 * - Handles nested back navigation by collapsing expanded item details first.
 */
@HiltViewModel
class AuditViewModel @Inject constructor(
    private val repository: AuditRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val logs: List<AuditResponseLogsInner> = emptyList(),
        val total: Int = 0,
        val page: Int = 1,
        val limit: Int = 20,
        val totalPages: Int = 1,
        val role: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        // Active filter inputs (staged before clicking Filtrar)
        val actionInput: String = "",
        val entityTypeInput: String = "",
        val userIdInput: String = "",
        // Applied filters (currently active on server query)
        val appliedAction: String = "",
        val appliedEntityType: String = "",
        val appliedUserId: Int? = null,
        // Expanded details entry id
        val expandedLogId: Int? = null,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN
        val canGoPrevious: Boolean get() = page > 1 && !loading
        val canGoNext: Boolean get() = page < totalPages && !loading
        val hasActiveFilters: Boolean
            get() = appliedAction.isNotEmpty() || appliedEntityType.isNotEmpty() || appliedUserId != null
    }

    private val _uiState = MutableStateFlow(UiState(role = authRepository.sessionRole()))
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        fetchLogs(page = 1)
    }

    fun onActionInputChanged(value: String) {
        _uiState.update { it.copy(actionInput = value) }
    }

    fun onEntityTypeInputChanged(value: String) {
        _uiState.update { it.copy(entityTypeInput = value) }
    }

    fun onUserIdInputChanged(value: String) {
        _uiState.update { it.copy(userIdInput = value) }
    }

    fun applyFilters() {
        val state = _uiState.value
        val parsedUserId = state.userIdInput.trim().toIntOrNull()
        _uiState.update {
            it.copy(
                appliedAction = state.actionInput.trim(),
                appliedEntityType = state.entityTypeInput.trim(),
                appliedUserId = parsedUserId,
            )
        }
        fetchLogs(page = 1)
    }

    fun clearFilters() {
        _uiState.update {
            it.copy(
                actionInput = "",
                entityTypeInput = "",
                userIdInput = "",
                appliedAction = "",
                appliedEntityType = "",
                appliedUserId = null,
            )
        }
        fetchLogs(page = 1)
    }

    fun goToPreviousPage() {
        val state = _uiState.value
        if (state.canGoPrevious) {
            fetchLogs(page = state.page - 1)
        }
    }

    fun goToNextPage() {
        val state = _uiState.value
        if (state.canGoNext) {
            fetchLogs(page = state.page + 1)
        }
    }

    fun toggleDetails(logId: Int) {
        _uiState.update { current ->
            val next = if (current.expandedLogId == logId) null else logId
            current.copy(expandedLogId = next)
        }
    }

    /**
     * Handles nested back navigation.
     * Collapses expanded log details first; returns true if consumed, false to bubble up.
     */
    fun handleBack(): Boolean {
        if (_uiState.value.expandedLogId != null) {
            _uiState.update { it.copy(expandedLogId = null) }
            return true
        }
        return false
    }

    private fun fetchLogs(page: Int) {
        val role = authRepository.sessionRole()
        if (role != RolePolicy.ADMIN) {
            _uiState.update {
                it.copy(
                    loading = false,
                    role = role,
                    logs = emptyList(),
                    message = "Acesso restrito a administradores.",
                    isError = true,
                )
            }
            return
        }

        _uiState.update { it.copy(loading = true, message = null, isError = false, role = role) }
        viewModelScope.launch {
            val currentState = _uiState.value
            val result = repository.queryAuditLogs(
                page = page,
                limit = currentState.limit,
                action = currentState.appliedAction.takeIf { it.isNotBlank() },
                entityType = currentState.appliedEntityType.takeIf { it.isNotBlank() },
                userId = currentState.appliedUserId,
            )
            when (result) {
                is AuditQueryResult.Success -> {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            logs = result.page.logs,
                            total = result.page.total,
                            page = result.page.page,
                            limit = result.page.limit,
                            totalPages = result.page.totalPages,
                            message = null,
                            isError = false,
                        )
                    }
                }
                is AuditQueryResult.Refused -> {
                    val msg = when (result.statusCode) {
                        403 -> "Acesso negado: permissão de administrador necessária."
                        400 -> "Parâmetros de consulta inválidos."
                        else -> "Erro ao carregar logs (${result.statusCode})."
                    }
                    _uiState.update {
                        it.copy(
                            loading = false,
                            message = msg,
                            isError = true,
                        )
                    }
                }
                is AuditQueryResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            loading = false,
                            message = "Não foi possível conectar ao servidor.",
                            isError = true,
                        )
                    }
                }
            }
        }
    }
}
