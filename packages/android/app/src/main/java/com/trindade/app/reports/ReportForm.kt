package com.trindade.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner

/**
 * The report form's own state: what the operator has answered, plus the offers the product tasks choose
 * from. Held apart from either screen's `UiState` so that the form does not know which screen is drawing
 * it -- the generator seeds an empty one, the edit surface will seed one from a report, and both hand the
 * same shape to the same composables.
 */
data class ReportFormState(
    /** One boolean per `check`, `check_assai` and `check_normal` task. */
    val checks: Map<Int, Boolean> = emptyMap(),
    /** One entry per `temperature` task, holding exactly `temperatureReadings` readings. */
    val temperatures: Map<Int, List<String>> = emptyMap(),
    /** The names selected per product-check task. */
    val selectedProducts: Map<Int, Set<String>> = emptyMap(),
    /** The product lists the server offered, or null while they have not arrived. */
    val offers: ProductsResponse? = null,
)

/**
 * One category of the form: its tasks, and its children's, nested.
 *
 * `readOnly` is the server's answer rendered rather than recomputed: this screen never decides whether an
 * operator may edit -- `canEdit` and `readOnly` come from the report and are passed straight through --
 * and in read-only mode the controls are disabled so that a tap cannot reach a callback that would send
 * nothing.
 */
@Composable
fun CategoryForm(
    category: CategoriesResponseCategoriesInner,
    children: List<CategoriesResponseCategoriesInner>,
    form: ReportFormState,
    readOnly: Boolean = false,
    onCheckChange: (Int, Boolean) -> Unit,
    onProductToggle: (Int, String, Boolean) -> Unit,
    onTemperatureChange: (Int, Int, String) -> Unit,
    depth: Int = 0,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = (depth * 16).dp)) {
        Text(text = category.namePt, style = MaterialTheme.typography.titleMedium)
        HorizontalDivider()

        category.tasks.forEach { task ->
            when (task.taskType) {
                ReportCategoryTasksInner.TaskType.check ->
                    CheckRow(
                        label = task.namePt,
                        checked = form.checks[task.id] == true,
                        readOnly = readOnly,
                        onCheckedChange = { onCheckChange(task.id, it) },
                    )

                ReportCategoryTasksInner.TaskType.check_assai ->
                    ProductCheckBlock(
                        task = task,
                        offered = form.offers?.assai.orEmpty().map { it.value },
                        selected = form.selectedProducts[task.id].orEmpty(),
                        readOnly = readOnly,
                        onToggle = { product, chosen -> onProductToggle(task.id, product, chosen) },
                    )

                ReportCategoryTasksInner.TaskType.check_normal ->
                    ProductCheckBlock(
                        task = task,
                        offered = form.offers?.normal.orEmpty().map { it.value },
                        selected = form.selectedProducts[task.id].orEmpty(),
                        readOnly = readOnly,
                        onToggle = { product, chosen -> onProductToggle(task.id, product, chosen) },
                    )

                ReportCategoryTasksInner.TaskType.temperature ->
                    TemperatureBlock(
                        task = task,
                        readings = form.temperatures[task.id].orEmpty(),
                        readOnly = readOnly,
                        onChange = { index, value -> onTemperatureChange(task.id, index, value) },
                    )
            }
        }

        // The children beside their parent, which is the shape the endpoint serves -- its order is
        // `COALESCE(parent_category_id, id), sort_order, id` -- and the shape the web's own component
        // draws. Recursing rather than iterating twice is what keeps a deeper tree rendering as it is,
        // with the indent as the only difference; each child is drawn with no children of its own because
        // the endpoint sends one level, and the recursion would still be the one that decided otherwise.
        children.forEach { child ->
            CategoryForm(
                category = child,
                children = emptyList(),
                form = form,
                readOnly = readOnly,
                onCheckChange = onCheckChange,
                onProductToggle = onProductToggle,
                onTemperatureChange = onTemperatureChange,
                depth = depth + 1,
            )
        }
    }
}

@Composable
private fun CheckRow(
    label: String,
    checked: Boolean,
    readOnly: Boolean = false,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // `enabled` rather than a guard inside the callback: the operator has to see that this is not the
        // state a report is edited in, and a disabled control says that before the tap instead of after it.
        Checkbox(checked = checked, onCheckedChange = onCheckedChange, enabled = !readOnly)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A check plus the products that belong to it.
 *
 * The products are chips rather than a list of checkboxes because they are a set chosen from a short
 * offer, and the count selected is the thing the operator is answering.
 */
@Composable
private fun ProductCheckBlock(
    task: ReportCategoryTasksInner,
    offered: List<String>,
    selected: Set<String>,
    readOnly: Boolean = false,
    onToggle: (String, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = task.namePt, style = MaterialTheme.typography.bodyLarge)
        }

        if (offered.isEmpty()) {
            // Said rather than shown as an empty row: no chips and no explanation would read as
            // "this task has no products", which is a different statement from "the list did not load".
            Text(
                text = "Lista de produtos indisponível.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            return@Column
        }

        offered.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { product ->
                    FilterChip(
                        selected = product in selected,
                        onClick = {
                            if (readOnly) return@FilterChip
                            onToggle(product, product !in selected)
                        },
                        label = { Text(product) },
                        enabled = !readOnly,
                    )
                }
            }
        }
    }
}

/**
 * A temperature task, with one field per reading the task declares.
 *
 * The number of fields comes from the task, not from what has been typed: an export refuses a report
 * missing a reading, so the form has to show how many it owes from the start rather than growing as
 * the operator fills it in.
 */
@Composable
private fun TemperatureBlock(
    task: ReportCategoryTasksInner,
    readings: List<String>,
    readOnly: Boolean = false,
    onChange: (Int, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = task.namePt, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))

        readings.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = {
                    if (readOnly) return@OutlinedTextField
                    onChange(index, it)
                },
                label = { Text("Leitura ${index + 1}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                enabled = !readOnly,
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }
    }
}
