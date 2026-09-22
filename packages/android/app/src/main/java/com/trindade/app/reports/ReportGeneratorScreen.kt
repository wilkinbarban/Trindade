package com.trindade.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R

/**
 * The report generator, stateless.
 *
 * The form is not drawn here: it is `CategoryForm`, in `ReportForm.kt`, because the report's own edit
 * surface draws the same form and two copies of it would drift. Which element each task type gets is
 * decided there, by the generated `taskType` enum, so a fifth type added to the server's list fails to
 * compile rather than silently rendering nothing. What stays here is the screen around the form: the way
 * to the history, the message, the created id and the submit button.
 *
 * Categories nest, and the SPA indents children; the form draws each root's children out of the
 * categories the endpoint serves, beside their parent, which is what the web's own `CategorySection`
 * does, with the indent as the only visual difference.
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

        // Built once, outside `items`, because the form state is one value for the whole list: building
        // it inside the item lambda would rebuild it for every category and hand each root a different
        // object for the same answers.
        val form = ReportFormState(state.checks, state.temperatures, state.selectedProducts, state.offers)

        items(state.categories.filter { it.parentCategoryId == null }) { category ->
            CategoryForm(
                category = category,
                children = state.categories.filter { it.parentCategoryId == category.id },
                form = form,
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
