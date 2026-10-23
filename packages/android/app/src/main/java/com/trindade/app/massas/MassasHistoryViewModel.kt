package com.trindade.app.massas

import com.trindade.app.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.network.MassaHistoryDay
import com.trindade.app.ui.UiText
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

@HiltViewModel
class MassasHistoryViewModel @Inject constructor(
    private val repository: MassasRepository,
) : ViewModel() {

    data class UiState(
        val days: List<MassaHistoryDay> = emptyList(),
        val isLoading: Boolean = false,
        val errorMessage: UiText? = null,
        val showHelpDialog: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, errorMessage = null)
            val result = repository.history()
            _state.value = _state.value.copy(
                isLoading = false,
                days = result ?: emptyList(),
                errorMessage = if (result == null) UiText.of(R.string.massas_history_error_load) else null,
            )
        }
    }

    fun toggleHelpDialog(show: Boolean) {
        _state.value = _state.value.copy(showHelpDialog = show)
    }
}
