package com.trindade.app.reports

import com.trindade.app.contract.models.CreateReportRequestItemsInner
import com.trindade.app.contract.models.CreateReportRequestTemperaturesInner
import com.trindade.app.contract.models.ReportCategoryTasksInner
import java.math.BigDecimal

/**
 * The rules that turn a filled form into the wire body, in one place, for both surfaces that send one.
 *
 * The generator and the report's edit screen fill the same form and submit the same two shapes, and the
 * server validates both bodies against one schema. The rules below are in no type signature -- which
 * tasks become `items`, which become `temperatures`, what happens to a blank reading, what a reading
 * carries as its `location` and `readingIndex` -- and each of them is silent when it is wrong: the
 * server answers that a temperature is missing and points the operator at a temperature that was
 * filled in. A second copy of them would drift, and the drift would land in the one place a client
 * cannot paper over, the body the server refuses.
 *
 * [missingTemperatureReadings] is the same rule read from the other side. A blank reading is left out of
 * the body above, and the server refuses a body that does not carry every reading the active temperature
 * tasks declare -- so a form that is about to send one has to know which readings it still owes before it
 * sends anything. That question is asked by both surfaces, for the same reason they share the body: the
 * answer either blocks a request the server would answer 400 with, or it lets the operator see what is
 * missing, and two implementations of it would be two ways for the same form to be wrong.
 */

/**
 * The `items` array: one entry per task that is not a temperature task, in the order the tasks arrive.
 *
 * The order is the caller's, which is the order the endpoint served the categories in, because
 * `associateBy` keeps a task's first position and a body whose order moved between two identical
 * submissions would make a diff of two payloads unreadable for no reason.
 *
 * A temperature task is deliberately absent: it belongs in `temperatures` and nowhere else, and sending
 * it in both would leave the server reading one of the two as authoritative.
 */
internal fun reportItems(
    tasks: Collection<ReportCategoryTasksInner>,
    checks: Map<Int, Boolean>,
    selectedProducts: Map<Int, Set<String>>,
): List<CreateReportRequestItemsInner> =
    tasks
        .filter { it.taskType != ReportCategoryTasksInner.TaskType.temperature }
        .map { task ->
            CreateReportRequestItemsInner(
                taskId = task.id,
                checked = checks[task.id] == true,
                // Null rather than an empty list: nothing selected and an empty selection are
                // the same statement, and the server treats the field as optional.
                selectedProducts = selectedProducts[task.id]?.toList()?.takeIf { it.isNotEmpty() },
            )
        }

/**
 * The `temperatures` array: one entry per non-blank reading, in the order the tasks and their readings
 * arrive.
 *
 * A reading's `location` is the task's own name -- `Câmara Fria 1` and the like -- not a free-text field
 * this client could fill with anything, and `readingIndex` is one-based because the server accepts 1
 * to 3. A blank reading is left out rather than sent as a zero: sending zero for a field nobody filled
 * would store a temperature nobody measured, and leaving it out lets the server say which reading is
 * actually missing.
 *
 * [reading] is the caller's parser, passed in rather than duplicated here. It carries the one thing the
 * wire shape cannot express -- whether a typed string is a number at all -- and the copy of it that
 * matters is the caller's own, so the two surfaces read a field exactly the same way by construction
 * rather than by agreement.
 */
internal fun reportTemperatures(
    tasks: Collection<ReportCategoryTasksInner>,
    temperatures: Map<Int, List<String>>,
    reading: (String) -> BigDecimal?,
): List<CreateReportRequestTemperaturesInner> =
    tasks
        .filter { it.taskType == ReportCategoryTasksInner.TaskType.temperature }
        .flatMap { task ->
            temperatures[task.id].orEmpty().mapIndexedNotNull { index, text ->
                reading(text)?.let { value ->
                    CreateReportRequestTemperaturesInner(
                        location = task.namePt,
                        value = value,
                        readingIndex = index + 1,
                    )
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
internal fun String.toReading(): BigDecimal? =
    trim().replace(',', '.').toBigDecimalOrNull()

/**
 * One declared reading of one temperature task: the task it belongs to, and its zero-based slot.
 *
 * The pair is what the form needs on both sides of the same question. The view models count how many
 * slots are still owed to decide whether the form may be sent, and the form marks exactly those fields,
 * so the caller that has an empty list of them knows the readings are complete and the caller drawing
 * them knows which ones are not. A bare task id or a bare index would answer half of it.
 *
 * Public rather than internal, unlike the rules beside it: the slot travels in the view models' `UiState`
 * and in `ReportFormState`, which are the screens' own state types, and a public state carrying a private
 * shape would force those properties closed as well. The rules themselves stay internal -- the screens never
 * ask them anything -- while the answer travels with the state.
 */
data class TemperatureSlot(val taskId: Int, val index: Int)

/**
 * The readings the form still owes: one slot per declared reading whose field carries no number.
 *
 * The server's rule, and not a client's guess at it. `validateTemperatures` in
 * `reports.command.service.ts` builds the set of `name:readingIndex` pairs from every active task of every
 * active temperature category and refuses a body that does not carry exactly that set, so a body assembled
 * out of a half-filled form is not an incomplete report the server tolerates -- it is a 400 whose sentence
 * ("Falta colocar as temperaturas no relatório.") points at the temperature as a whole. That is the failure
 * this function exists to keep from ever leaving the phone.
 *
 * **A reading is complete when it parses to a number, not when the field is non-empty.** A field holding
 * `abc` would be dropped by [reportTemperatures] for the same reason a blank one is, so a rule that only
 * looked for blanks would let exactly the body the server refuses onto the wire, and the operator would get
 * the server's sentence about a temperature they did believe they had filled in. The parser is the caller's
 * own -- the same one [reportTemperatures] is handed -- so the two halves of the rule cannot disagree about
 * what a reading is.
 *
 * The slots are the task's declared range, not the length of whatever list the form holds: a list that grew
 * only as far as the operator typed would answer "complete" for the readings it never created, and the
 * form's own seeding is what gives every declared slot a home. A reading the task does not declare is not
 * asked for here and is not this function's business.
 */
internal fun missingTemperatureReadings(
    tasks: Collection<ReportCategoryTasksInner>,
    temperatures: Map<Int, List<String>>,
    reading: (String) -> BigDecimal?,
): Set<TemperatureSlot> =
    tasks
        .filter { it.taskType == ReportCategoryTasksInner.TaskType.temperature }
        .flatMap { task ->
            (0 until task.temperatureReadings).mapNotNull { index ->
                val typed = temperatures[task.id]?.getOrNull(index).orEmpty()
                if (reading(typed) == null) TemperatureSlot(task.id, index) else null
            }
        }
        .toSet()
