package com.trindade.app.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The report generator's form.
 *
 * The state is one map per element type rather than a list of item objects, because the four types
 * hold different shapes and a single item type would have to make every field optional to cover them:
 * a check is a boolean, a product check is a set of names, and a temperature task is a fixed number of
 * readings. Keeping them apart means a check cannot accidentally carry a temperature.
 *
 * Everything loads together on open, because the screen is unusable until it has all three: the
 * categories to know what to ask, the offers to render the two product types, and the shift because
 * the server detects it and the form must not guess.
 */
@HiltViewModel
class ReportGeneratorViewModel @Inject constructor(
    private val repository: ReportsRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val categories: List<CategoriesResponseCategoriesInner> = emptyList(),
        val offers: ProductsResponse? = null,
        val turno: String? = null,
        /** One boolean per `check`, `check_assai` and `check_normal` task. */
        val checks: Map<Int, Boolean> = emptyMap(),
        /** One entry per `temperature` task, holding exactly `temperatureReadings` readings. */
        val temperatures: Map<Int, List<String>> = emptyMap(),
        /** The names selected per product-check task. */
        val selectedProducts: Map<Int, Set<String>> = emptyMap(),
        val message: String? = null,
    ) {
        val ready: Boolean get() = !loading && categories.isNotEmpty() && offers != null && turno != null
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, message = null) }
        viewModelScope.launch {
            val categories = repository.categories()
            val offers = repository.productOffers()
            val turno = repository.detectedTurno()

            // The shift is the one absence worth refusing outright. A form that opens without it would
            // have to guess which shift the report belongs to, and the server is the only party that
            // detects it; a wrong guess produces a report filed against the wrong shift.
            val message = if (turno == null) {
                "Não foi possível detectar o turno. Verifique a conexão e tente de novo."
            } else {
                null
            }

            _state.update { current ->
                current.copy(
                    loading = false,
                    categories = categories.orEmpty(),
                    offers = offers,
                    turno = turno,
                    temperatures = current.temperatures + initialReadings(categories.orEmpty()),
                    message = message,
                )
            }
        }
    }

    fun onCheckChange(taskId: Int, checked: Boolean) =
        _state.update { it.copy(checks = it.checks + (taskId to checked)) }

    fun onProductToggle(taskId: Int, product: String, selected: Boolean) =
        _state.update { current ->
            val currentSet = current.selectedProducts[taskId].orEmpty()
            val updated = if (selected) currentSet + product else currentSet - product
            current.copy(selectedProducts = current.selectedProducts + (taskId to updated))
        }

    fun onTemperatureChange(taskId: Int, index: Int, value: String) =
        _state.update { current ->
            val readings = current.temperatures[taskId].orEmpty().toMutableList()
            while (readings.size <= index) readings.add("")
            readings[index] = value
            current.copy(temperatures = current.temperatures + (taskId to readings))
        }

    /**
     * A temperature task gets its slots up front, empty, so the form always shows as many inputs as
     * the task declares. Growing them as the operator types would let a partly filled form look
     * complete, which is the state an export refuses for a missing reading.
     */
    private fun initialReadings(categories: List<CategoriesResponseCategoriesInner>): Map<Int, List<String>> =
        categories
            .flatMap { it.tasks }
            .filter { it.taskType == ReportCategoryTasksInner.TaskType.temperature }
            .associate { task -> task.id to List(task.temperatureReadings) { "" } }
}
