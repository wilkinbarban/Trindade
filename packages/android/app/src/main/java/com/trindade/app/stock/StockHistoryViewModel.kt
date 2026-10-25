package com.trindade.app.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockHistoryDaysInner
import com.trindade.app.ui.waMeLink
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * A past day of counting, as a place of its own.
 *
 * The counter answers what is on the shelf now; this answers what was counted that day, which is a
 * different question asked at a different moment. It reads and sends: the day is corrected where it is
 * counted, and this screen shows what that produced. Only what was counted is listed, because a table of
 * twenty-five rows with twenty-four zeroes hides the one row somebody came for.
 *
 * The role used to decide whether the visit was a reading or a correction. The correction is gone -- one
 * calculator per shelf -- so the ViewModel no longer needs to know who is asking, and the server keeps
 * enforcing its own boundary over the endpoint.
 */
@HiltViewModel
class StockHistoryViewModel @Inject constructor(
    private val repository: StockRepository,
) : ViewModel() {

    data class UiState(
        val days: List<StockHistoryDaysInner> = emptyList(),
        val date: String = "",
        val day: StockDay? = null,
        val number: String = "",
        val note: String = "",
        val loading: Boolean = false,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun message(): String = state.value.day?.let { stockMessage(it) } ?: ""

    fun emailBody(): String = state.value.day?.let { stockEmailBody(it) } ?: ""

    fun whatsappLink(): String? = waMeLink(state.value.number, message())

    fun mailtoLink(): String =
        mailtoLink(StockViewModel.EMAIL_TO, "Contagem de estoque ${state.value.date}", emailBody())

    fun onDateChange(date: String) {
        _state.value = _state.value.copy(date = date)
        if (date.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) {
            load()
        }
    }

    fun onNumberChange(value: String) {
        _state.value = _state.value.copy(number = value)
    }

    fun onNote(text: String) {
        _state.value = _state.value.copy(note = text)
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, failed = false)
            val days = repository.history() ?: emptyList()
            val date = _state.value.date.ifEmpty { days.firstOrNull()?.date ?: "" }
            val isValidDate = date.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))
            val day = if (isValidDate) repository.day(date) else null
            _state.value = _state.value.copy(
                days = days,
                date = date,
                day = day,
                loading = false,
                failed = isValidDate && day == null,
            )
        }
    }
}
