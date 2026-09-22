package com.trindade.app.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.contract.models.UpdateReportRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editing one report: the web's `ReportEditPage`, for the fields the app's detail screen does not edit.
 *
 * The detail screen shows a report; this one changes it. What belongs here is exactly what
 * `UpdateReportRequest` accepts and nothing else -- the shift, the notes, and the tasks with their
 * products and their readings -- so this class has no photo state to confuse with the body it sends:
 * the photos keep their own calls and the detail screen keeps them.
 *
 * The state is one map per element type, the generator's own shape, because the form is the same form
 * (`ReportForm.kt`) and it asks for the same three answers: a check is a boolean, a product check is a
 * set of names, and a temperature task is a fixed number of readings.
 *
 * **The edit window is the server's, and this class never computes it.** The report arrives with
 * `canEdit` and `readOnly` on it, and both are rendered as they came; the boundary -- which day, which
 * creator -- lives in the backend, and the SPA derives it nowhere either. A client that recomputed the
 * window would be a second implementation of a business rule, and a phone's clock and timezone are
 * exactly what a second implementation gets wrong.
 */
@HiltViewModel
class ReportEditViewModel @Inject constructor(
    private val repository: ReportsRepository,
) : ViewModel() {

    data class UiState(
        val loading: Boolean = true,
        val report: ReportResponseReport? = null,
        val categories: List<CategoriesResponseCategoriesInner> = emptyList(),
        val offers: ProductsResponse? = null,
        val turno: String? = null,
        val notes: String = "",
        /** One boolean per `check`, `check_assai` and `check_normal` task. */
        val checks: Map<Int, Boolean> = emptyMap(),
        /** One entry per `temperature` task, holding exactly `temperatureReadings` readings. */
        val temperatures: Map<Int, List<String>> = emptyMap(),
        /** The names selected per product-check task. */
        val selectedProducts: Map<Int, Set<String>> = emptyMap(),
        val submitting: Boolean = false,
        /** Set once the server accepted the update, which is what the screen leaves on. */
        val saved: Boolean = false,
        val message: String? = null,
    ) {
        /**
         * Whether the form is drawn read-only, read off the report rather than decided here.
         *
         * The answer is [isReadOnly], the predicate in `ReportWindow.kt`, and not a copy of it: the
         * detail screen asks the same question to decide whether to offer the way in, so two copies
         * would be two answers to one question -- a report the detail draws an edit action for while
         * this screen draws no save at all, which is the contradiction one of them would eventually be
         * edited into. That predicate is the SPA's own `readOnly ?? !canEdit`, and its second half is
         * the one that has to fail closed: a report the server did not explicitly mark editable is
         * drawn read-only, so an absent flag can only ever offer less than the server allows, never an
         * edit the server would answer with a 403. A missing report is read-only for the same reason:
         * there is nothing there to edit, and the load that failed says so.
         */
        val readOnly: Boolean get() = report?.isReadOnly() ?: true

        val ready: Boolean
            get() = !loading && report != null && categories.isNotEmpty() && offers != null && turno != null

        /** Submitting is offered only for a report the server called editable, and only once. */
        val canSubmit: Boolean get() = ready && !readOnly && !submitting && !saved
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * Identifies the newest load, so a save an older one started cannot announce itself on this screen.
     *
     * The history view models' own request token, and their reason: two requests really can be in the air
     * at once. This view model is scoped to the activity's store and outlives the composition that draws
     * it, so the write one arrival started keeps running after the operator leaves, and the arrival that
     * replaces it is a screen that write does not belong to. Without the token the stale answer is written
     * over the new screen's state -- a spinner it never set going, a form whose save is withheld, and then
     * `saved`, which is the very flag the next arrival leaves on.
     *
     * The write itself is not touched for it, and that is the difference between this and a cancellation.
     * Cancelling the coroutine would tear down the call it is awaiting, so an edit the operator submitted
     * and then left the screen for could be abandoned before the server ever saw it; a write the operator
     * asked for is work that should land. What the token drops is only that write's claim on a screen it no
     * longer belongs to: the request runs on and the server applies it whether or not this screen is still
     * there, and its answer writes nothing when it arrives. A read is treated the other way, and the
     * dashboard is where that shape already is: it cancels its read, because an answer nobody is waiting
     * for is waste -- no screen is left to draw it, and no fact about the report is lost by dropping it --
     * while a cancelled write is work the operator asked for that never happened.
     */
    private var newestLoad = 0

    /**
     * The report, the categories and the offers, together, the way the generator loads its own three.
     *
     * All three or nothing useful: the form cannot key a reading without the task that declares it, and
     * it cannot draw a product task without the offers, so a partial load is a form that looks editable
     * and is not.
     *
     * The seeding below is where the two shapes differ, and the difference is the reason this mapping
     * exists at all: a report stores its answers the way the wire carries them, and the form wants them
     * keyed by task.
     */
    fun load(reportId: Int) {
        // The arrival takes a token of its own before anything is read, and every save compares against
        // it: see [newestLoad] for why the write a previous arrival started is left running and why the
        // token, rather than a cancellation, is what keeps it off this screen. Both flags that write left
        // behind go back to false with the load. Both flags, because both are about a visit rather than
        // about the report, and this view model is scoped to the activity's store, so it outlives the
        // composition: the instance that answers here is the one the screen that is gone was talking to.
        //
        // `saved` is the obvious one. It means "the write this screen sent was accepted", and left
        // standing it would be read by the next arrival's `state.first { it.saved }` and close the
        // screen before the report it just asked for was ever drawn -- the sticky flag the route's own
        // comment says the reset is there to avoid.
        //
        // `submitting` is the same failure one step earlier. A write still in flight is a write a screen
        // that no longer exists started: this arrival cannot finish it and did not start it, so left
        // standing it would draw a spinner it never set going and a form whose save is withheld, and when
        // that write landed it would set `saved` -- exactly the stale close above, arriving a moment
        // later. The token is what stops that now: the request finishes on the server and the answer it
        // brings is dropped when it arrives, because the screen that started it is not this one.
        newestLoad++
        _state.update { it.copy(loading = true, message = null, saved = false, submitting = false) }

        viewModelScope.launch {
            val report = repository.report(reportId)
            val categories = repository.categories()
            val offers = repository.productOffers()
            val tasks = categories.orEmpty().flatMap { it.tasks }

            _state.update { current ->
                current.copy(
                    loading = false,
                    report = report,
                    categories = categories.orEmpty(),
                    offers = offers,
                    turno = report?.turno?.value,
                    notes = report?.notes.orEmpty(),
                    checks = report?.items.orEmpty().associate { it.taskId to it.checked },
                    selectedProducts = seedProducts(report),
                    temperatures = seedTemperatures(report, tasks),
                    message = if (report == null) NOT_FOUND else null,
                )
            }
        }
    }

    // ---- The form's mutators, named as the generator names its own ----

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
     * The shift, which the generator has no equivalent of: there the server detects it and the form
     * cannot choose it, while here it is one of the fields the report already carries and the operator
     * may correct.
     */
    fun onTurnoChange(turno: String) = _state.update { it.copy(turno = turno) }

    /** The notes, the other field this screen owns that the generator never asks for. */
    fun onNotesChange(notes: String) = _state.update { it.copy(notes = notes) }

    /**
     * Sends the update.
     *
     * The body is built from the form and never from the report that was loaded: every field below is
     * read from the state as it stands at this moment, so a screen that changed one product does not
     * send the other answers back as they arrived. The two arrays come from [reportItems] and
     * [reportTemperatures], the same functions the generator submits through, because the server
     * validates both bodies against one schema.
     *
     * Both arrays are complete by construction -- those functions walk every task of every category --
     * because the server replaces what it is sent rather than merging it: an array that left a task out
     * would not be a smaller edit, it would be an edit that deleted the task. The `takeIf` is the
     * payload's own rule and not an omission: a form with nothing to say about items or temperatures
     * sends no field at all, which the server leaves alone, rather than an empty array, which it would
     * read as "delete them".
     *
     * Whether the readings are complete is the server's call here as much as in the generator: it
     * answers 400 and names what is missing.
     *
     * **The write is not cancelled when the operator leaves the screen.** A load started afterwards takes
     * a new [newestLoad] token, and that is the whole of what happens to a save already in the air: the
     * request runs to its end and the server applies it, whether or not this screen is still there, because
     * the edit is work the operator asked for and cancelling the coroutine would tear down the call it is
     * awaiting before the server ever saw it. What the token drops is that write's claim on a screen it no
     * longer belongs to -- its answer arrives and writes nothing. A read is treated the other way, and the
     * dashboard is where that shape already is: it cancels its read, because an answer nobody is waiting
     * for is waste -- no screen is left to draw it -- while a write nobody is waiting for is still an edit
     * the operator made and expects to have landed.
     */
    fun submit() {
        val current = state.value
        if (!current.canSubmit) return
        val report = current.report ?: return

        // The arrival this save belongs to, read before the request is launched: a load that starts while
        // the write is in the air takes a newer token, and this save then belongs to a screen that is gone.
        val load = newestLoad

        _state.update { it.copy(submitting = true, message = null) }

        // `submitting` is set here as the write starts and cleared on every path that runs when it
        // finishes, and the token is what keeps a later arrival from being handed any of it: a save whose
        // token was superseded writes nothing at all -- not the flag, not `saved`, not the message.
        viewModelScope.launch {
            val tasksById = current.categories.flatMap { it.tasks }.associateBy { it.id }

            val body = UpdateReportRequest(
                turno = current.turno?.let(::updateTurno),
                // The web's own expression, `notes.trim() || null`: the field is trimmed, and a note
                // that is only whitespace goes as null rather than as an empty string. The two are not
                // the same thing to the server -- an empty string is a note whose text is empty, and
                // null is no note -- so a screen that saved the untrimmed field would store a note the
                // operator never wrote, and one that stored "" for a cleared field would leave the
                // report looking as if it had been answered.
                notes = current.notes.trim().ifEmpty { null },
                items = reportItems(tasksById.values, current.checks, current.selectedProducts)
                    .takeIf { it.isNotEmpty() },
                // Recorded wart, not repaired: this `takeIf` is the payload's own rule -- a form with
                // nothing to say about temperatures sends no field, which the server leaves alone,
                // rather than an empty array, which it would read as "delete them" -- but the two
                // halves of that rule do not agree with each other here. Blanking every reading leaves
                // this array empty, so the field is omitted and the server keeps the stored readings;
                // blanking one reading leaves the rest in the array, so the field is sent and the
                // cleared one is deleted. Clearing everything is therefore not something this form can
                // express, and it cannot be: a reading has no representation on the wire as blank (the
                // schema requires a value), so "no readings" and "the operator left this alone" are
                // one body. Fixing it is a decision about the wire -- an explicit null, or a replace
                // semantics the server states -- and it is recorded here rather than invented here.
                temperatures = reportTemperatures(tasksById.values, current.temperatures, String::toReading)
                    .takeIf { it.isNotEmpty() },
            )

            val result = repository.update(report.id, body)

            // A superseded save is dropped whole -- not `submitting`, not `saved`, not the message -- and
            // nothing is done to the request itself: it finished, the server answered, and that answer is
            // about a screen that no longer exists. Half of it written over a newer arrival would be worse
            // than none, because the flag and the form would then describe two different visits.
            if (load != newestLoad) return@launch

            _state.update { previous ->
                when (result) {
                    // Saved only means the server took the body: the screen leaves on it, and there is
                    // nothing left for this state to hold.
                    is ReportWriteResult.Saved -> previous.copy(submitting = false, saved = true)
                    is ReportWriteResult.Refused ->
                        previous.copy(submitting = false, message = refusalMessage(result.statusCode))
                    ReportWriteResult.Unreachable -> previous.copy(submitting = false, message = UNREACHABLE)
                }
            }
        }
    }

    /**
     * The report's flat readings, keyed back by the task they belong to, which is what the form wants.
     *
     * This is the seam where the two shapes differ, and it is not a cosmetic one. The wire carries one
     * `temperatures` array whose entries are `location`, `readingIndex` and `value`; the form holds one
     * list per temperature task. The `location` of an entry is the temperature task's own `namePt` --
     * that is what `ReportPayload` wrote when the report was created, so it is the identity the server
     * round-trips, and matching on it is matching on a value both sides can see rather than on the order
     * the entries happened to arrive in.
     *
     * Each value lands at its own `readingIndex - 1` in a list as long as the task's declared
     * `temperatureReadings`, and a slot the report has no reading for stays the empty string, which is
     * the form's own blank: the operator has to see every slot the export will demand instead of a list
     * that grew only as far as the data went.
     *
     * The number is rendered plainly rather than as `BigDecimal.toString()` chose: that can answer
     * `1E+2` for a reading of a hundred, and the string in the field is what the operator reads and
     * re-submits.
     */
    private fun seedTemperatures(
        report: ReportResponseReport?,
        tasks: List<ReportCategoryTasksInner>,
    ): Map<Int, List<String>> {
        val temperatureTasks = tasks.filter { it.taskType == ReportCategoryTasksInner.TaskType.temperature }
        val seeded = temperatureTasks.associate { it.id to List(it.temperatureReadings) { "" } }.toMutableMap()
        val tasksByName = temperatureTasks.associateBy { it.namePt }

        report?.temperatures.orEmpty().forEach { reading ->
            val task = tasksByName[reading.location] ?: return@forEach
            val index = reading.readingIndex - 1

            // A reading the server indexed at or below zero has no slot to land in, and the form has
            // nowhere to show it: the readings it owes run from 1.
            if (index < 0) return@forEach

            val readings = seeded[task.id].orEmpty().toMutableList()
            while (readings.size <= index) readings.add("")
            readings[index] = reading.value.toPlainString()
            seeded[task.id] = readings
        }

        return seeded
    }

    /**
     * The products each product task had selected, keyed by task id.
     *
     * A task the report carries no `selectedProducts` for is left out rather than seeded with an empty
     * set, because the two say the same thing to the form -- nothing selected -- and [reportItems] turns
     * both into a null field, which is the wire's own way of saying it.
     */
    private fun seedProducts(report: ReportResponseReport?): Map<Int, Set<String>> =
        report?.items.orEmpty()
            .mapNotNull { item -> item.selectedProducts?.let { item.taskId to it.toSet() } }
            .toMap()

    /**
     * The request's turno enum, matched by its value.
     *
     * The report carries one enum for the shift and the request another with the same two values, so the
     * answer the form holds has to be mapped across. A value the request's enum does not carry leaves the
     * field out of the body rather than sending a guess.
     */
    private fun updateTurno(selected: String): UpdateReportRequest.Turno? =
        UpdateReportRequest.Turno.entries.firstOrNull { it.value == selected }

    /**
     * What to say about a refusal, by the status code the server answered with.
     *
     * 409 and 400 are the generator's own sentences, code for code, because the two routes refuse the
     * same body: 409 is the shift and date the create route guards, and 400 is the payload -- a reading
     * set that is not complete, or a constraint the transaction refused.
     *
     * 403 is not, and it is the one code whose meaning is not the same on the two routes. On this one it
     * is exactly the edit-window refusal: `reports.command.service.ts` refuses a PATCH the permissions do
     * not allow, and a report outside the window is the only way this operator's own report can be
     * refused for a reason no retry will change. The generator's generic sentence -- "try again" -- would
     * send the operator round a loop with no exit, so the sentence here is the detail screen's own, which
     * already says what that code means and is the same answer about the same report.
     *
     * Everything else is the generic sentence rather than a guess at a cause this client cannot see.
     */
    private fun refusalMessage(statusCode: Int): String = when (statusCode) {
        409 -> "Já existe um relatório para esse turno nesta data."
        400 -> "Relatório incompleto. Preencha todas as temperaturas e tente de novo."
        403 -> OUTSIDE_EDIT_WINDOW
        else -> GENERIC
    }

    private companion object {
        const val NOT_FOUND = "Relatório não encontrado."
        const val UNREACHABLE = "Sem conexão com o servidor. Verifique a rede e tente de novo."

        /**
         * The refusal the detail screen already owns, reused rather than written again.
         *
         * The words are `ReportDetailViewModel`'s, character for character, and that is the point: 403
         * on a report is one fact about one report, and the two screens that can be looking at it must
         * not describe it in two different ways. The class that owns the sentence for its own screen is
         * the one that wrote it first, and this one borrows it deliberately instead of inventing a
         * second copy that could drift.
         */
        const val OUTSIDE_EDIT_WINDOW = "Fora do prazo de edição: este relatório é somente leitura."

        /** The generator's own generic failure, which fits a screen that saves as much as it fits that one. */
        const val GENERIC = "Não foi possível salvar o relatório. Tente de novo."
    }
}
