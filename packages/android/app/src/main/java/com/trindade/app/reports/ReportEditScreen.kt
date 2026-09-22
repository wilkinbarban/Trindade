package com.trindade.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.UpdateReportRequest
import kotlinx.coroutines.flow.first

/**
 * The report edit screen, stateless.
 *
 * The form is not drawn here: it is `CategoryForm`, in `ReportForm.kt`, because the generator draws the
 * same form and the two would drift. That is not a cosmetic risk on this surface -- the save sends the
 * whole `items` array back, so a task this screen did not draw is a task the next save deletes. What
 * stays here is what the edit surface owns and the generator does not have: the shift and the notes,
 * which are fields of a stored report rather than answers the create asks for, plus the way back and the
 * save action.
 *
 * **Nothing here decides whether the report may be edited.** `state.readOnly` arrives already computed
 * by `ReportEditViewModel` from the report's own `readOnly` and `canEdit`, both of which are the
 * server's; the window (which day, which creator) lives in the backend, and a screen that recomputed it
 * would be a second implementation of a business rule -- the one a phone's clock and timezone get wrong.
 *
 * The scroll container is the screen's own, for the reason the form lane gives: this surface is taller
 * than a phone, and a node nobody can reach is not a control.
 */
@Composable
fun ReportEditScreen(
    state: ReportEditViewModel.UiState,
    onTurnoChange: (String) -> Unit,
    onNotesChange: (String) -> Unit,
    onCheckChange: (Int, Boolean) -> Unit,
    onProductToggle: (Int, String, Boolean) -> Unit,
    onTemperatureChange: (Int, Int, String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Built once, outside the category loop, for the reason the generator's own copy gives: the form
    // state is one value for the whole screen, and building it per category would hand each root a
    // different object for the same answers.
    val form = ReportFormState(state.checks, state.temperatures, state.selectedProducts, state.offers)

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The same way out the detail draws, with this screen's own title beside it: the two are one
        // row because back belongs to the screen whose heading it sits with.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
            Text(text = stringResource(R.string.report_edit), style = MaterialTheme.typography.titleLarge)
        }

        // The shift, as two chips. Exactly one of them is selected because the state holds one value:
        // `turno` is an enum on the wire and the view model seeds it from the report, so there is no
        // third answer and no "none" for this screen to draw. The values handed to `onTurnoChange` are
        // the request enum's own, which is also what the view model matches the body against -- the
        // report's enum is a second type for the same two shifts, and passing a value across from the
        // wrong one would leave the body without a `turno` at all.
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = state.turno == UpdateReportRequest.Turno.tarde.value,
                onClick = { onTurnoChange(UpdateReportRequest.Turno.tarde.value) },
                label = { Text(stringResource(R.string.history_turno_tarde)) },
                enabled = !state.readOnly,
            )
            FilterChip(
                selected = state.turno == UpdateReportRequest.Turno.noite.value,
                onClick = { onTurnoChange(UpdateReportRequest.Turno.noite.value) },
                label = { Text(stringResource(R.string.history_turno_noite)) },
                enabled = !state.readOnly,
            )
        }

        // Several lines because this is where an operator says what happened in prose, and a single line
        // would hide all but the first clause of it while they type.
        OutlinedTextField(
            value = state.notes,
            onValueChange = onNotesChange,
            label = { Text(stringResource(R.string.report_notes)) },
            minLines = 3,
            enabled = !state.readOnly,
            modifier = Modifier.fillMaxWidth(),
        )

        // One form per root category, with its children filtered out of the same list the generator
        // serves them from: the endpoint sends one level, ordered `COALESCE(parent_category_id, id),
        // sort_order, id`, so a child belongs to the root whose id it names.
        state.categories.filter { it.parentCategoryId == null }.forEach { category ->
            CategoryForm(
                category = category,
                children = state.categories.filter { it.parentCategoryId == category.id },
                form = form,
                readOnly = state.readOnly,
                onCheckChange = onCheckChange,
                onProductToggle = onProductToggle,
                onTemperatureChange = onTemperatureChange,
            )
        }

        state.message?.let { message ->
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        // D6, rendered: when the server says this report cannot be edited, the save is not drawn at all
        // -- not drawn disabled. A disabled action is still an offer, and it reads as one with a reason
        // to find; here there is nothing for the operator to find and nothing to fix, because the
        // boundary is the server's window and no edit from this screen can cross it. So the screen says
        // it by withholding the action, and the sentence explaining the window is the server's to send
        // through `state.message` if it ever wants to -- which is also why nothing here is a string.
        // The controls above are drawn disabled rather than left live, as the web draws its own
        // (`readOnly` reaches every input there too): a live field would collect an edit this screen has
        // no action to send.
        if (!state.readOnly) {
            Button(
                onClick = onSubmit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(stringResource(R.string.report_update))
                }
            }
        }
    }
}

/**
 * The screen with its ViewModel attached.
 *
 * The load is keyed on the report, so arriving at one always reads it -- once per arrival, and not once
 * per recomposition.
 *
 * The save is reported by waiting for it rather than by keying an effect on the flag, and the reason is
 * this view model's scope: it is scoped to the activity's store, so it outlives this composition. A
 * `saved` flag left true by a previous visit would be read by the first composition of the next one, and
 * an effect keyed on it would close the screen before the load this arrival just asked for ever landed.
 * Waiting on the flow is what makes "a save" mean the write *this* arrival sent -- `load` clears the flag
 * for the same reason -- and it needs no assumption about the order two effects start in.
 */
@Composable
fun ReportEditRoute(
    reportId: Int,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: ReportEditViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(reportId) {
        viewModel.load(reportId)
        viewModel.state.first { it.saved }
        onSaved()
    }

    ReportEditScreen(
        state = state,
        onTurnoChange = viewModel::onTurnoChange,
        onNotesChange = viewModel::onNotesChange,
        onCheckChange = viewModel::onCheckChange,
        onProductToggle = viewModel::onProductToggle,
        onTemperatureChange = viewModel::onTemperatureChange,
        onSubmit = viewModel::submit,
        onBack = onBack,
    )
}
