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
