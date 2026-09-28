package com.trindade.app.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class TimeSlotsViewModel @Inject constructor(
    private val repository: TimeSlotsRepository,
    private val authRepository: AuthRepository,
) : ViewModel() {
    data class UiState(
        val loading: Boolean = true,
        val saving: Boolean = false,
        val timeSlots: List<String> = emptyList(),
        val input: String = "",
        val role: String? = null,
        val currentUserId: Int? = null,
        val error: String? = null,
        val refusedStatus: Int? = null,
    ) {
        val isAdmin: Boolean get() = role == ADMIN
        fun canAdd(): Boolean = isAdmin
        fun canRemove(slot: String): Boolean = isAdmin
        fun canRemove(): Boolean = isAdmin
        fun canMutate(): Boolean = isAdmin
        val slots: List<String> get() = timeSlots
        val slotInput: String get() = input
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()
    private var reading: Job? = null

    /** Reload on arrival and after writes; cancelling prevents stale responses replacing the newest catalog. */
    fun load() {
        reading?.cancel()
        _state.update { it.copy(loading = true, error = null, refusedStatus = null) }
        reading = viewModelScope.launch {
            val profile = authRepository.profile()
            val slots = repository.timeSlots()
            if (profile == null || slots == null) {
                // A failed read cannot authorize PUT of a replacement list built from an empty catalog.
                _state.update {
                    it.copy(
                        loading = false,
                        error = UNREACHABLE,
                        timeSlots = emptyList(),
                        role = null,
                        currentUserId = null,
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    loading = false,
                    timeSlots = slots,
                    role = profile.role,
                    currentUserId = profile.id,
                    error = null,
                )
            }
        }
    }

    fun onInputChange(value: String) = changeForm { copy(input = value) }
    fun onSlotInputChange(value: String) = onInputChange(value)

    fun addTimeSlot(slot: String? = null) {
        val current = _state.value
        if (current.loading || current.saving || !current.isAdmin) return

        val candidate = (slot ?: current.input).trim()
        if (candidate.isEmpty()) return

        val updated = (current.timeSlots + candidate).sorted()
        write(clearInput = true) { repository.update(updated) }
    }

    fun addSlot(slot: String? = null) = addTimeSlot(slot)
    fun add(slot: String? = null) = addTimeSlot(slot)

    fun removeTimeSlot(slot: String) {
        val current = _state.value
        if (current.loading || current.saving || !current.isAdmin) return

        val updated = current.timeSlots.filter { it != slot }
        if (updated.isEmpty()) {
            _state.update { it.copy(error = AT_LEAST_ONE_SLOT, refusedStatus = null) }
            return
        }

        write { repository.update(updated) }
    }

    fun removeSlot(slot: String) = removeTimeSlot(slot)
    fun remove(slot: String) = removeTimeSlot(slot)

    private fun write(clearInput: Boolean = false, call: suspend () -> TimeSlotWriteResult) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, error = null, refusedStatus = null) }
        viewModelScope.launch { finishWrite(call(), clearInput) }
    }

    private fun finishWrite(result: TimeSlotWriteResult, clearInput: Boolean = false) {
        when (result) {
            is TimeSlotWriteResult.Saved -> {
                _state.update {
                    it.copy(
                        saving = false,
                        input = if (clearInput) "" else it.input,
                        error = null,
                        refusedStatus = null,
                    )
                }
                load()
            }
            is TimeSlotWriteResult.Refused -> _state.update {
                it.copy(saving = false, error = REFUSED, refusedStatus = result.status)
            }
            TimeSlotWriteResult.Unreachable -> _state.update {
                it.copy(saving = false, error = UNREACHABLE, refusedStatus = null)
            }
        }
    }

    private fun changeForm(change: UiState.() -> UiState) =
        _state.update { it.change().copy(error = null, refusedStatus = null) }

    companion object {
        const val ADMIN = "Administrador"
        const val WORKER = "Trabalhador"
        const val AT_LEAST_ONE_SLOT = "At least one time slot is required."
        const val LAST_SLOT_REMOVAL = AT_LEAST_ONE_SLOT
        const val CANNOT_REMOVE_LAST = AT_LEAST_ONE_SLOT
        const val REFUSED = "The server refused this time-slot operation."
        const val UNREACHABLE = "Could not reach the server. Try again."
    }
}
