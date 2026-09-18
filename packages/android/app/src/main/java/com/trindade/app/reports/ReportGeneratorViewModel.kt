package com.trindade.app.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.CreateReportRequest
import com.trindade.app.contract.models.CreateReportRequestItemsInner
import com.trindade.app.contract.models.CreateReportRequestTemperaturesInner
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
        val submitting: Boolean = false,
        /** The report the server created, which is what the next screen needs. */
        val createdReportId: Int? = null,
        val message: String? = null,
    ) {
        val ready: Boolean get() = !loading && categories.isNotEmpty() && offers != null && turno != null

        val canSubmit: Boolean get() = ready && !submitting && createdReportId == null
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
     * Sends the report.
     *
     * The payload follows what the SPA sends, verified rather than assumed: a temperature reading's
     * `location` is the task's own name -- `Câmara Fria 1` and the like -- not a free-text field this
     * client could fill with anything, and `readingIndex` is one-based because the server accepts 1 to
     * 3.
     *
     * Whether the readings are complete is left to the server. It answers 400 naming the missing
     * temperatures, and duplicating that rule here would give the client a second place to be wrong
     * about what a complete report is.
     */
    fun submit() {
        val current = state.value
        if (!current.canSubmit) return
        _state.update { it.copy(submitting = true, message = null) }

        viewModelScope.launch {
            val tasksById = current.categories.flatMap { it.tasks }.associateBy { it.id }

            val items = tasksById.values
                .filter { it.taskType != ReportCategoryTasksInner.TaskType.temperature }
                .map { task ->
                    CreateReportRequestItemsInner(
                        taskId = task.id,
                        checked = current.checks[task.id] == true,
                        // Null rather than an empty list: nothing selected and an empty selection are
                        // the same statement, and the server treats the field as optional.
                        selectedProducts = current.selectedProducts[task.id]?.toList()?.takeIf { it.isNotEmpty() },
                    )
                }

            val temperatures = tasksById.values
                .filter { it.taskType == ReportCategoryTasksInner.TaskType.temperature }
                .flatMap { task ->
                    current.temperatures[task.id].orEmpty().mapIndexedNotNull { index, text ->
                        text.toReading()?.let { value ->
                            CreateReportRequestTemperaturesInner(
                                location = task.namePt,
                                value = value,
                                readingIndex = index + 1,
                            )
                        }
                    }
                }

            val result = repository.create(
                CreateReportRequest(
                    turno = current.turno?.let(::requestTurno),
                    items = items.takeIf { it.isNotEmpty() },
                    temperatures = temperatures.takeIf { it.isNotEmpty() },
                ),
            )

            _state.update { previous ->
                when (result) {
                    is ReportWriteResult.Saved -> previous.copy(submitting = false, createdReportId = result.report.id)
                    is ReportWriteResult.Refused -> previous.copy(submitting = false, message = refusalMessage(result.statusCode))
                    ReportWriteResult.Unreachable -> previous.copy(submitting = false, message = UNREACHABLE)
                }
            }
        }
    }

    /**
     * A typed reading, or null when the field is empty or not a number.
     *
     * The comma is not a nicety. Portuguese writes decimals with one, an operator in a cold room types
     * `4,5`, and `toDoubleOrNull` on that string returns null -- so the reading would be dropped and
     * the server would answer that a temperature is missing, pointing at the wrong problem.
     */
    private fun String.toReading(): java.math.BigDecimal? =
        trim().replace(',', '.').toBigDecimalOrNull()

    /**
     * The request's turno enum, matched by its value.
     *
     * The generator emits a separate enum for every DTO that carries one, so the detected shift and
     * the request's own type are different classes with the same values and have to be mapped.
     */
    private fun requestTurno(detected: String): CreateReportRequest.Turno? =
        CreateReportRequest.Turno.entries.firstOrNull { it.value == detected }

    private fun refusalMessage(statusCode: Int): String = when (statusCode) {
        // A report for that shift and date already exists; the server refuses a second one.
        409 -> "Já existe um relatório para esse turno nesta data."
        // The body failed validation, which for temperatures means a reading is missing.
        400 -> "Relatório incompleto. Preencha todas as temperaturas e tente de novo."
        else -> "Não foi possível salvar o relatório. Tente de novo."
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

    private companion object {
        const val UNREACHABLE = "Sem conexão com o servidor. Verifique a rede e tente de novo."
    }
}
