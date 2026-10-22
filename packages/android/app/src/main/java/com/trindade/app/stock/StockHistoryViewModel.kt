package com.trindade.app.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.auth.AuthRepository
import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockDayProductsInner
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
 * different question asked at a different moment. Two rules come with it. Only what was counted is listed,
 * because a table of twenty-five rows with twenty-four zeroes hides the one row somebody came for. And the
 * role decides whether the visit is a reading or a correction: a Trabajador reads, and an Administrador may
 * correct the day. The server enforces the same boundary with its 403, and this guard exists so nobody is
 * offered a button that will be refused -- not so the boundary can live here.
 */
@HiltViewModel
class StockHistoryViewModel @Inject constructor(
    private val repository: StockRepository,
    private val authRepository: AuthRepository? = null,
) : ViewModel() {

    companion object {
        const val EDIT_ROLE = "Administrador"

        /** Who may correct a past day. The same string the server checks. */
        fun canEdit(role: String?): Boolean = role == EDIT_ROLE
    }

    data class UiState(
        val days: List<StockHistoryDaysInner> = emptyList(),
        val date: String = "",
        val day: StockDay? = null,
        val role: String? = null,
        val quantities: Map<String, String> = emptyMap(),
        val blurred: Set<String> = emptySet(),
        val focused: String? = null,
        val number: String = "",
        val note: String = "",
        val loading: Boolean = false,
        val failed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Only the products that were counted: the rest would be a wall of zeroes. */
    fun counted(): List<StockDayProductsInner> = (state.value.day?.products ?: emptyList()).filter { it.units > 0 }

    fun canEdit(): Boolean = canEdit(state.value.role)

    fun visibleErrors(): List<String> =
        visibleBoxErrors(state.value.quantities, state.value.blurred, state.value.focused)

    fun message(): String = state.value.day?.let { stockMessage(it) } ?: ""

    fun emailBody(): String = state.value.day?.let { stockEmailBody(it) } ?: ""

    fun whatsappLink(): String? = waMeLink(state.value.number, message())

    fun mailtoLink(): String =
        mailtoLink(StockViewModel.EMAIL_TO, "Contagem de estoque ${state.value.date}", emailBody())

    fun onRoleKnown(role: String?) {
        _state.value = _state.value.copy(role = role)
    }

    fun onDateChange(date: String) {
        _state.value = _state.value.copy(date = date)
        if (date.matches(Regex("""^\d{4}-\d{2}-\d{2}$"""))) {
            load()
        }
    }

    fun onQuantityChange(code: String, value: String) {
        _state.value = _state.value.copy(
            quantities = _state.value.quantities + (code to value),
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

    fun onNote(text: String) {
        _state.value = _state.value.copy(note = text)
    }

    fun load() {
        if (state.value.role == null) {
            authRepository?.sessionRole()?.let { onRoleKnown(it) }
        }
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

    fun addEntry(product: StockDayProductsInner) {
        if (!canEdit()) {
            onNote("Seu perfil não corrige contagens.")
            return
        }
        val raw = _state.value.quantities[product.code] ?: ""
        if (addDisabled(raw)) {
            onNote("Digite uma quantidade inteira de caixas maior que zero.")
            return
        }
        val boxes = raw.trim().toInt()
        viewModelScope.launch {
            when (repository.addEntry(_state.value.date, product.code, boxes)) {
                is StockRepository.WriteResult.Saved -> {
                    _state.value = _state.value.copy(quantities = _state.value.quantities + (product.code to ""))
                    onNote("Caixas somadas ao total.")
                    load()
                }
                StockRepository.WriteResult.Refused -> onNote("O servidor recusou: revise a quantidade.")
                StockRepository.WriteResult.Unreachable -> onNote("Não foi possível falar com o servidor.")
            }
        }
    }

    fun resetProduct(product: StockDayProductsInner) {
        if (!canEdit()) {
            onNote("Seu perfil não corrige contagens.")
            return
        }
        viewModelScope.launch {
            when (repository.resetProduct(_state.value.date, product.code)) {
                is StockRepository.WriteResult.Saved -> {
                    onNote("Produto zerado.")
                    load()
                }
                StockRepository.WriteResult.Refused -> onNote("O servidor recusou o reset.")
                StockRepository.WriteResult.Unreachable -> onNote("Não foi possível falar com o servidor.")
            }
        }
    }
}
