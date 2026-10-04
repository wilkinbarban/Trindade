package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

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

    fun cancelDelete() {
        if (_uiState.value.deleting) return
        _uiState.update { it.copy(deleteConfirmSlot = null) }
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
    }
}
