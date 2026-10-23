package com.trindade.app.stock

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.R
import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockDayProductsInner
import com.trindade.app.ui.UiText
import com.trindade.app.ui.waMeLink
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The counter's state and the two things it may ask the server to do.
 *
 * The screen never computes: it collects state and calls these methods. The validation is not here either
 * but in [StockBoxes], because whether a count is possible is a rule and not a rendering detail. What this
 * class owns is the moment -- which tab is open, which field was left, which one is being typed in -- and
 * the two writes, whose three outcomes it keeps apart so a refusal is never shown as an unreachable server.
 *
 * The day is read as São Paulo's rather than the device's, which is the same decision the web makes: a
 * count is a document about a day and a phone timed elsewhere must not label it with tomorrow's date.
 */
@HiltViewModel
class StockViewModel @Inject constructor(private val repository: StockRepository) : ViewModel() {

    /** The mailbox the plant reads the counts in. Never the author's address. */
    companion object {
        const val EMAIL_TO = "xmitrindade@hotmail.com"
        const val EDIT_ROLE = "Administrador"

        fun canEdit(role: String?): Boolean = role == EDIT_ROLE

        fun saopauloToday(): String {
            val format = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            format.timeZone = TimeZone.getTimeZone("America/Sao_Paulo")
            return format.format(java.util.Date())
        }
    }

    data class UiState(
        val date: String = saopauloToday(),
        val day: StockDay? = null,
        val role: String? = null,
        val tab: String? = null,
        val quantities: Map<String, String> = emptyMap(),
        val blurred: Set<String> = emptySet(),
        val focused: String? = null,
        val number: String = "",
        val note: UiText? = null,
        val loading: Boolean = false,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun canEdit(): Boolean = canEdit(state.value.role)

    fun onRoleKnown(role: String?) {
        _state.value = _state.value.copy(role = role)
    }

    /** The catalogue of the day, split into the two sections the counter shows, in the order it arrived. */
    fun groups(): Map<String, List<StockDayProductsInner>> =
        (state.value.day?.products ?: emptyList()).groupBy { it.category }

    fun visibleErrors(): List<String> =
        visibleBoxErrors(state.value.quantities, state.value.blurred, state.value.focused)

    fun hasSomethingToSend(): Boolean = (state.value.day?.totalBoxes ?: 0) > 0

    fun message(): String = state.value.day?.let { stockMessage(it) } ?: ""

    fun emailBody(): String = state.value.day?.let { stockEmailBody(it) } ?: ""

    fun whatsappLink(): String? = waMeLink(state.value.number, message())

    fun mailtoLink(): String =
        mailtoLink(EMAIL_TO, "Contagem de estoque ${state.value.date}", emailBody())

    fun onDateChange(date: String) {
        _state.value = _state.value.copy(date = date)
        if (date.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) {
            load()
        }
    }

    fun onTabChange(category: String) {
        _state.value = _state.value.copy(tab = category)
    }

    fun onQuantityChange(code: String, value: String) {
        _state.value = _state.value.copy(
            quantities = _state.value.quantities + (code to value),
            // Typing again clears that field's warning: the correction is under way.
            blurred = _state.value.blurred - code,
        )
    }

    fun onFocus(code: String) {
        _state.value = _state.value.copy(focused = code)
    }

    fun onBlur(code: String) {
        _state.value = _state.value.copy(blurred = _state.value.blurred + code, focused = null)
    }

    fun onNumberChange(value: String) {
        _state.value = _state.value.copy(number = value)
    }

    fun onNote(@StringRes resId: Int, vararg args: Any) {
        _state.value = _state.value.copy(note = UiText.of(resId, *args))
    }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, failed = false)
            val isValidDate = _state.value.date.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))
            val day = if (isValidDate) repository.day(_state.value.date) else null
            _state.value = _state.value.copy(
                day = day,
                loading = false,
                failed = isValidDate && day == null,
                tab = _state.value.tab ?: day?.products?.firstOrNull()?.category,
            )
        }
    }

    fun addEntry(product: StockDayProductsInner) {
        if (!canEdit()) {
            onNote(R.string.stock_note_not_admin)
            return
        }
        val raw = _state.value.quantities[product.code] ?: ""
        if (addDisabled(raw)) {
            onNote(R.string.stock_note_invalid_boxes)
            return
        }
        val boxes = raw.trim().toInt()
        viewModelScope.launch {
            when (repository.addEntry(_state.value.date, product.code, boxes)) {
                is StockRepository.WriteResult.Saved -> {
                    _state.value = _state.value.copy(quantities = _state.value.quantities + (product.code to ""))
                    onNote(R.string.stock_note_boxes_added)
                    load()
                }
                StockRepository.WriteResult.Refused -> onNote(R.string.stock_note_server_refused)
                StockRepository.WriteResult.Unreachable -> onNote(R.string.stock_note_offline)
            }
        }
    }

    fun resetProduct(product: StockDayProductsInner) {
        if (!canEdit()) {
            onNote(R.string.stock_note_not_admin)
            return
        }
        viewModelScope.launch {
            when (repository.resetProduct(_state.value.date, product.code)) {
                is StockRepository.WriteResult.Saved -> {
                    onNote(R.string.stock_note_reset_done)
                    load()
                }
                StockRepository.WriteResult.Refused -> onNote(R.string.stock_note_reset_refused)
                StockRepository.WriteResult.Unreachable -> onNote(R.string.stock_note_offline)
            }
        }
    }
}
