package com.trindade.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ReportCategoryTasksInner
import com.trindade.app.R

/**
 * The report generator, stateless.
 *
 * The four element types are decided by `task.taskType`, which is a generated enum rather than a
 * string, so the `when` below is exhaustive: a fifth type added to the server's list would fail to
 * compile here rather than silently render nothing.
 *
 * Categories nest, and the SPA indents children; this renders one level of nesting by recursion, which
 * a deeper tree would still handle, with the indent as the only visual difference.
 */
@Composable
fun ReportGeneratorScreen(
    state: ReportGeneratorViewModel.UiState,
    onCheckChange: (Int, Boolean) -> Unit,
    onProductToggle: (Int, String, Boolean) -> Unit,
    onTemperatureChange: (Int, Int, String) -> Unit,
    onSubmit: () -> Unit,
    onOpenHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The history lives here rather than in a third tab, and that is the deliberate part of this
        // placement: the history lists the records this screen produces, so the way to them is an
        // action on the surface that makes them. The tab row stays the app's two jobs -- Relatórios and
        // Horários -- because the SPA's flat sidebar of four destinations is a desktop directory, not
        // a phone's navigation.
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.report_history)) }
            }
        }

        state.message?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        // Shown once the server has answered with an id, because that id is the one thing the next
        // screen needs and a form that just stops being submittable would look like a failure.
        state.createdReportId?.let { id ->
            item {
                Text(
                    text = stringResource(R.string.report_created, id),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        items(state.categories.filter { it.parentCategoryId == null }) { category ->
            CategoryBlock(
                category = category,
                state = state,
                onCheckChange = onCheckChange,
                onProductToggle = onProductToggle,
                onTemperatureChange = onTemperatureChange,
            )
        }

        item {
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.report_submit))
                }
            }
        }
    }
}

@Composable
private fun CategoryBlock(
    category: CategoriesResponseCategoriesInner,
    state: ReportGeneratorViewModel.UiState,
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
                        checked = state.checks[task.id] == true,
                        onCheckedChange = { onCheckChange(task.id, it) },
                    )

                ReportCategoryTasksInner.TaskType.check_assai ->
                    ProductCheckBlock(
                        task = task,
                        offered = state.offers?.assai.orEmpty().map { it.value },
                        selected = state.selectedProducts[task.id].orEmpty(),
                        onToggle = { product, chosen -> onProductToggle(task.id, product, chosen) },
                    )

                ReportCategoryTasksInner.TaskType.check_normal ->
                    ProductCheckBlock(
                        task = task,
                        offered = state.offers?.normal.orEmpty().map { it.value },
                        selected = state.selectedProducts[task.id].orEmpty(),
                        onToggle = { product, chosen -> onProductToggle(task.id, product, chosen) },
                    )

                ReportCategoryTasksInner.TaskType.temperature ->
                    TemperatureBlock(
                        task = task,
                        readings = state.temperatures[task.id].orEmpty(),
                        onChange = { index, value -> onTemperatureChange(task.id, index, value) },
                    )
            }
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
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
                        onClick = { onToggle(product, product !in selected) },
                        label = { Text(product) },
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
    onChange: (Int, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = task.namePt, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))

        readings.forEachIndexed { index, value ->
            OutlinedTextField(
                value = value,
                onValueChange = { onChange(index, it) },
                label = { Text("Leitura ${index + 1}") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            )
        }
    }
}

/** The screen with its ViewModel attached. */
@Composable
fun ReportGeneratorRoute(
    onCreated: (Int) -> Unit,
    onOpenHistory: () -> Unit,
    viewModel: ReportGeneratorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Fired once per id rather than on every recomposition, so navigating away and back does not
    // re-enter the detail screen from a result that was already acted on.
    androidx.compose.runtime.LaunchedEffect(state.createdReportId) {
        state.createdReportId?.let(onCreated)
    }

    ReportGeneratorScreen(
        state = state,
        onCheckChange = viewModel::onCheckChange,
        onProductToggle = viewModel::onProductToggle,
        onTemperatureChange = viewModel::onTemperatureChange,
        onSubmit = viewModel::submit,
        onOpenHistory = onOpenHistory,
    )
}
