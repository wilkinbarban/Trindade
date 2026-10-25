package com.trindade.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.HelpSection
import com.trindade.app.ui.components.ModuleHelpButton
import com.trindade.app.ui.components.ModuleHelpDialog
import com.trindade.app.ui.components.NavigationActionButton

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
    onNotesChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onOpenHistory: () -> Unit,
    onRetry: () -> Unit,
    onTurnoChange: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showHelpDialog by remember { mutableStateOf(false) }

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
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NavigationActionButton(
                    onClick = onOpenHistory,
                    text = stringResource(R.string.report_history),
                )
                ModuleHelpButton(onClick = { showHelpDialog = true })
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

        // A read that could not supply a piece the form is made of says which piece, and offers the way to
        // try again. The reason is what separates this screen from one that drew an empty form: the
        // operator reads a fact about the read instead of guessing what a blank page means.
        if (state.stage == ReportGeneratorViewModel.Stage.FAILED ||
            state.stage == ReportGeneratorViewModel.Stage.INCOMPLETE
        ) {
            items(state.missing) { prerequisite ->
                Text(
                    text = stringResource(missingMessage(prerequisite)),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            item {
                NavigationActionButton(
                    onClick = onRetry,
                    text = stringResource(R.string.report_retry),
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
        // object for the same answers. The missing readings travel with it, so the temperature block
        // marks the very fields the sentence above the save is about.
        val form = ReportFormState(
            checks = state.checks,
            temperatures = state.temperatures,
            selectedProducts = state.selectedProducts,
            offers = state.offers,
            missingReadings = state.missingReadings,
        )

        when (state.stage) {
            ReportGeneratorViewModel.Stage.LOADING -> item {
                CircularProgressIndicator()
            }

            // The two halves that can draw something: the whole form when everything arrived, and the form
            // the server did supply when only the product lists are missing. What is left of the form cannot
            // be answered, so it is not drawn at all -- the reasons above are what the operator reads.
            ReportGeneratorViewModel.Stage.READY,
            ReportGeneratorViewModel.Stage.INCOMPLETE,
            -> {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Turno",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .selectable(
                                            selected = state.turno == "tarde",
                                            role = Role.RadioButton,
                                            onClick = { onTurnoChange("tarde") },
                                        )
                                        .padding(end = 8.dp),
                                ) {
                                    RadioButton(
                                        selected = state.turno == "tarde",
                                        onClick = null,
                                    )
                                    Text(
                                        text = "🌅 Tarde",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .defaultMinSize(minHeight = 48.dp)
                                        .selectable(
                                            selected = state.turno == "noite",
                                            role = Role.RadioButton,
                                            onClick = { onTurnoChange("noite") },
                                        )
                                        .padding(end = 8.dp),
                                ) {
                                    RadioButton(
                                        selected = state.turno == "noite",
                                        onClick = null,
                                    )
                                    Text(
                                        text = "🌙 Noite",
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                            }
                        }
                    }
                }

                // Keyed by the category's own id, so the item's identity survives a reload: an unkeyed
                // list is indexed by position, and a read that served the same categories in another
                // order would move a category's drawn subtree to whichever slot it now sits in, taking
                // the field the operator was typing in with it. With the key, Compose moves the item
                // rather than reusing the slot, which is what keeps a half-filled form in place while
                // the operator reads it.
                items(state.categories.filter { it.parentCategoryId == null }, key = { it.id }) { category ->
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
                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = onNotesChange,
                        label = { Text(stringResource(R.string.report_notes)) },
                        minLines = 3,
                        enabled = !state.submitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }

                // The reason the save below is withheld, said where the operator is looking for the
                // action: without it a form that owes a reading is a form whose button does nothing, and
                // the sentence the server would answer 400 with only ever arrives for a body the client
                // no longer has any reason to send. The fields themselves carry the same information one
                // reading at a time (see `TemperatureBlock`), which is what makes the sentence actionable
                // on a form taller than the screen.
                if (state.showValidation && form.missingReadings.isNotEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.report_temperatures_required),
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                item {
                    AdminPrimaryButton(
                        onClick = onSubmit,
                        text = stringResource(R.string.report_submit),
                        enabled = state.ready && !state.submitting && state.createdReportId == null,
                        loading = state.submitting,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            }

            ReportGeneratorViewModel.Stage.FAILED -> Unit
        }
    }

    if (showHelpDialog) {
        ModuleHelpDialog(
            title = stringResource(R.string.help_reports_title),
            description = stringResource(R.string.help_reports_desc),
            sections = listOf(
                HelpSection(
                    title = "Checklist e Turnos",
                    description = "Preencha as tarefas operacionais de início ou fim de turno e selecione o turno correspondente.",
                ),
                HelpSection(
                    title = "Temperaturas e Câmaras",
                    description = "Registre as medições de temperatura das câmaras frias para garantir o padrão de qualidade e auditoria.",
                ),
            ),
            onDismissRequest = { showHelpDialog = false },
        )
    }
}

/** The sentence one missing prerequisite is read as, in the operator's own language. */
@androidx.annotation.StringRes
private fun missingMessage(prerequisite: ReportGeneratorViewModel.Prerequisite): Int = when (prerequisite) {
    ReportGeneratorViewModel.Prerequisite.SHIFT -> R.string.report_missing_shift
    ReportGeneratorViewModel.Prerequisite.CATEGORIES -> R.string.report_missing_categories
    ReportGeneratorViewModel.Prerequisite.OFFERS -> R.string.report_missing_offers
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
        state.createdReportId?.let { id ->
            viewModel.onReportCreatedHandled()
            onCreated(id)
        }
    }

    ReportGeneratorScreen(
        state = state,
        onCheckChange = viewModel::onCheckChange,
        onProductToggle = viewModel::onProductToggle,
        onTemperatureChange = viewModel::onTemperatureChange,
        onNotesChange = viewModel::onNotesChange,
        onSubmit = viewModel::submit,
        onOpenHistory = onOpenHistory,
        onRetry = viewModel::retry,
        onTurnoChange = viewModel::selectTurno,
    )
}
