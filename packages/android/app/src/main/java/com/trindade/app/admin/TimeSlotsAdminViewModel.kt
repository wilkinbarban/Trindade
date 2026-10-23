package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.R
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
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
        val validationError: UiText? = null,
    ) {
        val canSubmit: Boolean
            get() = !saving
    }

    data class UiState(
        val loading: Boolean = true,
        val timeSlots: List<String> = emptyList(),
        val form: FormState? = null,
        val role: String? = null,
        val message: UiText? = null,
        val isError: Boolean = false,
        val deleteConfirmSlot: String? = null,
        val deleting: Boolean = false,
    ) {
        val isAdmin: Boolean get() = role == RolePolicy.ADMIN
        val canRemove: Boolean get() = isAdmin && timeSlots.size > 1
    }

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null
    private var loadGeneration = 0L

    fun loadData() {
        loadJob?.cancel()
        val generation = ++loadGeneration
        _uiState.update { it.copy(loading = true, message = null) }
        loadJob = viewModelScope.launch {
            val profile = authRepository.profile()
            if (generation != loadGeneration) return@launch

            if (profile == null) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        role = null,
                        timeSlots = emptyList(),
                        form = null,
                        deleteConfirmSlot = null,
                        message = ERROR_LOAD,
                        isError = true,
                    )
                }
                return@launch
            }

            val role = profile.role
            if (role != RolePolicy.ADMIN) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        role = role,
                        timeSlots = emptyList(),
                        form = null,
                        deleteConfirmSlot = null,
                        message = ERROR_ADMIN_ONLY,
                        isError = true,
                    )
                }
                return@launch
            }

            when (val result = repository.fetchTimeSlots()) {
                is TimeSlotsReadResult.Success -> {
                    if (generation != loadGeneration) return@launch
                    _uiState.update { current ->
                        current.copy(
                            loading = false,
                            role = role,
                            timeSlots = result.timeSlots.sorted(),
                            message = null,
                            isError = false,
                        )
                    }
                }
                is TimeSlotsReadResult.Refused -> {
                    if (generation != loadGeneration) return@launch
                    if (result.statusCode == 403) {
                        _uiState.update {
                            it.copy(
                                loading = false,
                                role = null,
                                timeSlots = emptyList(),
                                form = null,
                                deleteConfirmSlot = null,
                                message = ERROR_ADMIN_ONLY,
                                isError = true,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                loading = false,
                                timeSlots = emptyList(),
                                form = null,
                                deleteConfirmSlot = null,
                                message = ERROR_LOAD,
                                isError = true,
                            )
                        }
                    }
                }
                is TimeSlotsReadResult.Unreachable -> {
                    if (generation != loadGeneration) return@launch
                    _uiState.update {
                        it.copy(
                            loading = false,
                            timeSlots = emptyList(),
                            form = null,
                            deleteConfirmSlot = null,
                            message = ERROR_LOAD,
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    fun openAddForm() {
        val current = _uiState.value
        if (!current.isAdmin || current.deleting) return
        _uiState.update {
            it.copy(
                form = FormState(),
                deleteConfirmSlot = null,
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
        val current = _uiState.value
        val form = current.form ?: return
        if (form.saving || current.deleting || !current.isAdmin) return

        val raw = form.slotInput.trim()
        if (raw.isEmpty()) {
            _uiState.update {
                it.copy(form = form.copy(validationError = VALIDATION_REQUIRED))
            }
            return
        }

        if (!isValidTimeSlot(raw)) {
            _uiState.update {
                it.copy(form = form.copy(validationError = VALIDATION_FORMAT))
            }
            return
        }

        val normalized = normalizeTimeSlot(raw)
        if (current.timeSlots.contains(normalized)) {
            _uiState.update {
                it.copy(form = form.copy(validationError = VALIDATION_DUPLICATE))
            }
            return
        }

        val updatedSlots = (current.timeSlots + normalized).distinct().sorted()

        _uiState.update { it.copy(form = form.copy(saving = true, validationError = null), message = null) }

        viewModelScope.launch {
            when (val result = repository.updateTimeSlots(updatedSlots)) {
                is TimeSlotsAdminWriteResult.Saved -> {
                    _uiState.update {
                        it.copy(
                            form = null,
                            timeSlots = result.timeSlots.sorted(),
                            message = ADDED_SUCCESS,
                            isError = false,
                        )
                    }
                }
                is TimeSlotsAdminWriteResult.Refused -> {
                    if (result.statusCode == 403) {
                        _uiState.update {
                            it.copy(
                                form = null,
                                deleteConfirmSlot = null,
                                role = null,
                                timeSlots = emptyList(),
                                message = ERROR_ADMIN_ONLY,
                                isError = true,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                form = form.copy(saving = false),
                                message = ERROR_SAVE,
                                isError = true,
                            )
                        }
                    }
                }
                is TimeSlotsAdminWriteResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            form = form.copy(saving = false),
                            message = ERROR_UNREACHABLE,
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    fun requestDelete(slot: String) {
        val current = _uiState.value
        if (!current.isAdmin || current.deleting || current.form?.saving == true) return
        if (current.timeSlots.size <= 1) {
            _uiState.update {
                it.copy(
                    message = ERROR_AT_LEAST_ONE,
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
        val current = _uiState.value
        if (current.deleting || current.form?.saving == true || !current.isAdmin) return
        val slot = current.deleteConfirmSlot ?: return
        removeTimeSlot(slot)
    }

    fun removeTimeSlot(slot: String) {
        val current = _uiState.value
        if (!current.isAdmin || current.deleting || current.form?.saving == true) return

        if (current.timeSlots.size <= 1) {
            _uiState.update {
                it.copy(
                    deleteConfirmSlot = null,
                    message = ERROR_AT_LEAST_ONE,
                    isError = true,
                )
            }
            return
        }

        if (!current.timeSlots.contains(slot)) {
            _uiState.update { it.copy(deleteConfirmSlot = null) }
            return
        }

        val remaining = current.timeSlots.filter { it != slot }.sorted()
        if (remaining.isEmpty()) {
            _uiState.update {
                it.copy(
                    deleteConfirmSlot = null,
                    message = ERROR_AT_LEAST_ONE,
                    isError = true,
                )
            }
            return
        }

        _uiState.update { it.copy(deleting = true, message = null) }

        viewModelScope.launch {
            when (val result = repository.updateTimeSlots(remaining)) {
                is TimeSlotsAdminWriteResult.Saved -> {
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            deleteConfirmSlot = null,
                            timeSlots = result.timeSlots.sorted(),
                            message = REMOVED_SUCCESS,
                            isError = false,
                        )
                    }
                }
                is TimeSlotsAdminWriteResult.Refused -> {
                    if (result.statusCode == 403) {
                        _uiState.update {
                            it.copy(
                                deleting = false,
                                deleteConfirmSlot = null,
                                role = null,
                                timeSlots = emptyList(),
                                form = null,
                                message = ERROR_ADMIN_ONLY,
                                isError = true,
                            )
                        }
                    } else {
                        _uiState.update {
                            it.copy(
                                deleting = false,
                                deleteConfirmSlot = null,
                                message = ERROR_REMOVE,
                                isError = true,
                            )
                        }
                    }
                }
                is TimeSlotsAdminWriteResult.Unreachable -> {
                    _uiState.update {
                        it.copy(
                            deleting = false,
                            deleteConfirmSlot = null,
                            message = ERROR_UNREACHABLE,
                            isError = true,
                        )
                    }
                }
            }
        }
    }

    fun handleBack(): Boolean {
        val current = _uiState.value
        if (current.deleting) return true
        if (current.deleteConfirmSlot != null) {
            cancelDelete()
            return true
        }
        if (current.form != null) {
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

        val ERROR_LOAD = UiText.of(R.string.admin_time_slots_error_load)
        val ERROR_ADMIN_ONLY = UiText.of(R.string.admin_time_slots_error_admin_only)
        val VALIDATION_REQUIRED = UiText.of(R.string.admin_time_slots_validate_required)
        val VALIDATION_FORMAT = UiText.of(R.string.admin_time_slots_validate_format)
        val VALIDATION_DUPLICATE = UiText.of(R.string.admin_time_slots_validate_duplicate)
        val ADDED_SUCCESS = UiText.of(R.string.admin_time_slots_added_success)
        val ERROR_SAVE = UiText.of(R.string.admin_time_slots_error_save)
        val ERROR_UNREACHABLE = UiText.of(R.string.admin_time_slots_error_unreachable)
        val ERROR_AT_LEAST_ONE = UiText.of(R.string.admin_time_slots_error_at_least_one)
        val REMOVED_SUCCESS = UiText.of(R.string.admin_time_slots_removed_success)
        val ERROR_REMOVE = UiText.of(R.string.admin_time_slots_error_remove)
    }
}
