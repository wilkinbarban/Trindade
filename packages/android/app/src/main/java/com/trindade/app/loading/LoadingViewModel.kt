package com.trindade.app.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LoadingViewModel @Inject constructor(
    private val repository: LoadingRepository,
) : ViewModel() {

    data class UiState(
        val date: String = saoPauloToday(),
        val loading: Boolean = true,
        val schedules: List<SchedulesResponseSchedulesInner> = emptyList(),
        val timeSlots: List<String> = emptyList(),
        val message: String? = null,
    ) {
        /**
         * The slots to draw: the configured ones, plus any slot an entry already sits in.
         *
         * The union rather than the configured list alone, because a slot removed from settings after
         * entries were made would otherwise make those entries disappear from the screen while still
         * existing on the server -- the worst of both, since the operator cannot see or remove them.
         */
        val slots: List<String>
            get() = (timeSlots + schedules.map { it.timeSlot }).distinct().sorted()

        fun entriesIn(slot: String): List<SchedulesResponseSchedulesInner> =
            schedules.filter { it.timeSlot == slot }

        /** What the counter shows. A display: see [FleteroQuota] for why it may not refuse anything. */
        fun fleteroCountIn(slot: String): Int = FleteroQuota.countInWindow(schedules, slot)

        fun isExceeded(slot: String): Boolean = FleteroQuota.isExceeded(schedules, slot)
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun onDateChange(date: String) {
        _state.update { it.copy(date = date) }
        load()
    }

    fun load(date: String = state.value.date) {
        _state.update { it.copy(date = date, loading = true, message = null) }

        viewModelScope.launch {
            val schedules = repository.schedules(date)
            val slots = repository.timeSlots()

            _state.update {
                it.copy(
                    loading = false,
                    schedules = schedules.orEmpty(),
                    timeSlots = slots.orEmpty(),
                    // Only the schedules are worth refusing over: without them the grid is empty, while
                    // a missing slot list still leaves the day's entries visible in their own slots.
                    message = if (schedules == null) UNREACHABLE else null,
                )
            }
        }
    }

    companion object {
        const val UNREACHABLE = "Sem conexão com o servidor. Verifique a rede e tente de novo."

        /**
         * Today in São Paulo, not on the device.
         *
         * The schedule belongs to a day, and a phone with the wrong timezone would otherwise open the
         * previous or next day's grid -- showing an empty screen for a day that has entries, or worse,
         * inviting an operator to add one that already exists.
         */
        fun saoPauloToday(): String = LocalDate.now(ZoneId.of("America/Sao_Paulo")).toString()
    }
}
