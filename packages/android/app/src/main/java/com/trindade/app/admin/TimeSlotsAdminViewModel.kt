package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the loading time slot configuration state, inline form editing, and server synchronization.
 *
 * Implements permissions and business rules mirroring the web client (AdminDashboard.tsx):
 * - [isAdmin]: whether current session role is Administrador.
 * - Time slot management is admin-only: only an Administrador can view or update time slots.
 * - Non-admin workers are refused access.
 * - Server PUT replaces the whole set atomically; preserves validation, ordering, and duplicate handling.
 */
@HiltViewModel
class TimeSlotsAdminViewModel @Inject constructor(
    private val repository: TimeSlotsAdminRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {

    data class FormState(
        val slotInput: String = "",
        val saving: Boolean = false,
        val validationError: String? = null,
    ) {
        val canSubmit: Boolean
            get() = !saving
    }

    data class UiState(
        val loading: Boolean = true,
        val timeSlots: List<String> = emptyList(),
        val form: FormState? = null,
        val role: String? = null,
        val message: String? = null,
        val isError: Boolean = false,
        val deleteConfirmSlot: String? = null,
        val deleting: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN
        val canRemove: Boolean get() = isAdmin && timeSlots.size > 1
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
                        timeSlots = emptyList(),
                        message = "Acesso restrito a administradores.",
                        isError = true,
                    )
                }
                return@launch
            }

            val slots = repository.timeSlots()
            _uiState.update { current ->
                if (slots == null) {
                    current.copy(
                        loading = false,
                        role = role,
                        message = "Não foi possível carregar os horários. Tente de novo.",
                        isError = true,
                    )
                } else {
                    current.copy(
                        loading = false,
                        role = role,
                        timeSlots = slots.sorted(),
                        message = null,
                        isError = false,
                    )
                }
            }
        }
    }

    fun openAddForm() {
        if (!_uiState.value.isAdmin) return
        _uiState.update {
            it.copy(
                form = FormState(),
                message = null,
            )
        }
    }

    fun closeForm() {
        if (_uiState.value.form?.saving == true) return
        _uiState.update { it.copy(form = null) }
    }

    fun onSlotInputChanged(input: String) {
        _uiState.update { current ->
            current.copy(
                form = current.form?.copy(
                    slotInput = input,
                    validationError = null,
                ),
            )
        }
    }

    fun addTimeSlot() {
        val form = _uiState.value.form ?: return
        if (form.saving || !_uiState.value.isAdmin) return

        val raw = form.slotInput.trim()
        if (raw.isEmpty()) {
            _uiState.update {
                it.copy(form = form.copy(validationError = "Horário é obrigatório."))
            }
            return
        }

        if (!isValidTimeSlot(raw)) {
            _uiState.update {
                it.copy(form = form.copy(validationError = "Formato inválido. Use HH:MM (ex: 08:00)."))
            }
            return
        }

        val normalized = normalizeTimeSlot(raw)
        if (_uiState.value.timeSlots.contains(normalized)) {
            _uiState.update {
                it.copy(form = form.copy(validationError = "Horário já cadastrado."))
            }
            return
        }

        val updatedSlots = (_uiState.value.timeSlots + normalized).distinct().sorted()

        _uiState.update { it.copy(form = form.copy(saving = true, validationError = null), message = null) }

        viewModelScope.launch {
            when (val result = repository.updateTimeSlots(updatedSlots)) {
                is TimeSlotsWriteResult.Saved -> {
                    _uiState.update {
                        it.copy(
                            form = null,
                            timeSlots = result.timeSlots.sorted(),
                            message = "Horário adicionado com sucesso.",
                            isError = false,
                        )
                    }
                }
                is TimeSlotsWriteResult.Refused -> {
                    _uiState.update {
                        it.copy(
                            form = form.copy(saving = false),
                            message = "Não foi possível salvar os horários (${result.statusCode}).",
                            isError = true,
                        )
                    }
                }
                is TimeSlotsWriteResult.Unreachable -> {
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

    fun requestDelete(slot: String) {
        if (!_uiState.value.isAdmin) return
        if (_uiState.value.timeSlots.size <= 1) {
            _uiState.update {
                it.copy(
                    message = "É necessário manter pelo menos um horário.",
                    isError = true,
                )
            }
            return
        }
        _uiState.update { it.copy(deleteConfirmSlot = slot, message = null) }
    }

    fun cancelDelete() {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleteConfirmSlot = null) }
    }

    fun confirmDelete() {
        val slot = _uiState.value.deleteConfirmSlot ?: return
        removeTimeSlot(slot)
    }

    fun removeTimeSlot(slot: String) {
        if (!_uiState.value.isAdmin) return

        if (_uiState.value.timeSlots.size <= 1) {
            _uiState.update {
                it.copy(
                    deleteConfirmSlot = null,
                    message = "É necessário manter pelo menos um horário.",
                    isError = true,
                )
            }
            return
        }

        val remaining = _uiState.value.timeSlots.filter { it != slot }.sorted()
        if (remaining.isEmpty()) {
            _uiState.update {
                it.copy(
                    deleteConfirmSlot = null,
                    message = "É necessário manter pelo menos um horário.",
                    isError = true,
                )
            }
            return
        }

        _uiState.update { it.copy(deleting = true, message = null) }

        viewModelScope.launch {
            when (val result = repository.updateTimeSlots(remaining)) {
                is TimeSlotsWriteResult.Saved -> {
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            deleteConfirmSlot = null,
                            timeSlots = result.timeSlots.sorted(),
                            message = "Horário removido com sucesso.",
                            isError = false,
                        )
                    }
                }
                is TimeSlotsWriteResult.Refused -> {
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            deleteConfirmSlot = null,
                            message = "Não foi possível remover o horário (${result.statusCode}).",
                            isError = true,
                        )
                    }
                }
                is TimeSlotsWriteResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            deleteConfirmSlot = null,
                            message = "Sem conexão com o servidor. Tente de novo.",
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    fun handleBack(): Boolean {
        if (_uiState.value.deleteConfirmSlot != null) {
            cancelDelete()
            return true
        }
        if (_uiState.value.form != null) {
            closeForm()
            return true
        }
        return false
    }

    companion object {
        private val TIME_SLOT_REGEX = Regex("^([0-1]?[0-9]|2[0-3]):[0-5][0-9]$")

        fun isValidTimeSlot(raw: String): Boolean =
            TIME_SLOT_REGEX.matches(raw.trim())

        fun normalizeTimeSlot(raw: String): String {
            val trimmed = raw.trim()
            val parts = trimmed.split(":")
            return "${parts[0].padStart(2, '0')}:${parts[1]}"
        }
    }
}
