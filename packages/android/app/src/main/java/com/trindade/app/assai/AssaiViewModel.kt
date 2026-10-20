package com.trindade.app.assai

import androidx.lifecycle.ViewModel
import com.trindade.app.ui.waMeLink
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * What the order screen holds while it is open.
 *
 * The arithmetic is not here: this holds what the operator typed and where the cursor is, and asks
 * [assaiLines] and its siblings what that means. Keeping the two apart is what lets the same rule serve
 * the phone and the desk, and it is why this file has nothing to mock -- there is no repository because
 * an order is not persisted until it is sent.
 */
@HiltViewModel
class AssaiViewModel @Inject constructor() : ViewModel() {

    data class UiState(
        val quantities: Map<String, String> = emptyMap(),
        val blurred: Set<String> = emptySet(),
        val focused: String? = null,
        val selectedDay: String? = null,
        val freeNumber: String = "",
        val note: String = "",
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The typed order, judged product by product. */
    fun lines(): List<AssaiLine> = assaiLines(state.value.quantities)

    /** The products whose warning belongs on screen right now. */
    fun visibleErrors(): List<String> =
        assaiVisibleErrors(lines(), state.value.blurred, state.value.focused)

    fun totalBoxes(): Int = assaiTotalBoxes(lines())

    fun hasError(): Boolean = assaiHasError(lines())

    fun message(): String = assaiMessage(lines(), state.value.selectedDay)

    /**
     * The chat link for the order, or nothing when there is no usable number.
     *
     * A number the link cannot carry offers no action rather than an action that opens an empty chat.
     */
    fun whatsappLink(): String? = waMeLink(state.value.freeNumber, message())

    fun onQuantityChange(product: String, value: String) {
        _state.value = _state.value.copy(
            quantities = _state.value.quantities + (product to value),
            // Typing again clears the warning of that field: the correction is under way.
            blurred = _state.value.blurred - product,
        )
    }

    fun onFocus(product: String) {
        _state.value = _state.value.copy(focused = product)
    }

    fun onBlur(product: String) {
        _state.value = _state.value.copy(blurred = _state.value.blurred + product, focused = null)
    }

    fun onNumberChange(value: String) {
        _state.value = _state.value.copy(freeNumber = value)
    }

    fun onDaySelect(day: String?) {
        _state.value = _state.value.copy(
            selectedDay = if (_state.value.selectedDay == day) null else day
        )
    }

    fun onNote(text: String) {
        _state.value = _state.value.copy(note = text)
    }
}
