package com.trindade.app.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import kotlinx.coroutines.flow.first

/**
 * The loading edit screen: one row, moved to another slot of its own day.
 *
 * It is small because the web's page is small. A loading is a schedule of driver, vehicle and slot rows
 * and not a report -- there are no items, no categories and no photos -- so nothing of the report edit
 * surface transfers here except its shape, and the shape is what this file follows: a stateless
 * `@Composable` that draws the state it is handed and calls the callbacks it is given, and a `Route`
 * below that collects that state and wires the view model.
 *
 * **Nothing here decides anything.** `state.readOnly` arrives already computed by [LoadingEditViewModel]
 * from the row's own two flags -- both of them the server's -- and `state.canSubmit` arrives computed
 * too, so this screen recomputes neither question: it draws the save wherever the state says one may be
 * sent and withholds it wherever the state says the row is closed. The window those flags encode lives
 * in the backend (`loading.service.ts`, `projectHistoryPermissions(actor, existing, 'one-hour')`), and a
 * screen that derived it would be a second implementation of a business rule, with the phone's clock and
 * timezone as its inputs.
 *
 * **A read-only arrival draws no save at all.** Not a disabled one: a disabled action is still an offer,
 * and it reads as one with a reason to find, while here there is nothing to find and nothing to fix --
 * the boundary is the server's window and no edit from this screen can cross it. That is the rule
 * `ReportEditScreen` records in the same words, and the picker is drawn disabled rather than withheld for
 * the same reason that screen leaves its own fields drawn: a live picker would collect a choice this
 * screen has no action to send.
 *
 * **The refusal is the server's sentence**, drawn out of `state.message` exactly as it stands. The
 * one-hour window's sentence is written by the backend character for character and this screen is where
 * the operator reads it, so `strings.xml` holds no sentence of this app's for it and nothing here
 * paraphrases the one that arrives.
 *
 * The scroll container is the screen's own, for the reason the reports screen gives: a day's configured
 * slots can be taller than a phone, and a node nobody can reach is not a control.
 */
@Composable
fun LoadingEditScreen(
    state: LoadingEditViewModel.UiState,
    onTimeSlotChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // The same way out the reports screens draw, with this screen's own title beside it: the two are
        // one row because back belongs to the screen whose heading it sits with.
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
            Text(text = stringResource(R.string.loading_edit), style = MaterialTheme.typography.titleLarge)
        }

        // The read in flight, in the loading screens' own idiom: the spinner, and nothing else. The row a
        // previous arrival left is deliberately not drawn under it -- this view model is scoped to the
        // activity's store and outlives the composition, so `load` leaves the entry it last read standing
        // while it reads, and drawing it would show the operator one row while the arrival they asked for
        // is still on its way.
        if (state.loading) {
            CircularProgressIndicator()
        } else {
            // The row being edited: the identity the grid draws for it, from the grid's own `describe`,
            // beside the slot it sits in -- the row's own slot and not `state.timeSlot`, because the
            // picker may already have moved the selection, while this line is the thing being moved.
            //
            // `state.ready` is not asked here, and that is deliberate rather than an omission: the pieces
            // it joins are each drawn or withheld on their own terms below -- no entry draws no row, an
            // empty offer list draws no picker -- and the save is exactly as live as `canSubmit`, which
            // already carries `ready` among its clauses.
            state.entry?.let { entry ->
                Text(
                    text = listOf(describe(entry), entry.timeSlot).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )

                // The picker: one option per configured slot of the day, in the server's own order, each
                // carrying the count that slot would hold with this row in it. The figure is the view
                // model's, asked of the same `FleteroQuota` the grid's counter comes from, and the
                // denominator printed beside it is the same one that counter prints.
                Text(
                    text = stringResource(R.string.loading_pick_slot),
                    style = MaterialTheme.typography.labelLarge,
                )
                state.offers.forEach { offer ->
                    // The whole row is the control, and the radio beside the label is the picture of the
                    // choice rather than a second, smaller target for it: a `selectable` row is one node
                    // that carries the slot, the count and the choice together, which is also how the
                    // operator reads it -- an option is "06:00, one fletero" and not a circle and then two
                    // facts. The button is handed no click of its own for that reason, and that is the
                    // shape Material's own `RadioButton` documents for a row that owns the selection.
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = state.timeSlot == offer.timeSlot,
                                enabled = !state.readOnly,
                                role = Role.RadioButton,
                                onClick = { onTimeSlotChange(offer.timeSlot) },
                            ),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.timeSlot == offer.timeSlot,
                            onClick = null,
                            enabled = !state.readOnly,
                        )
                        Text(text = offer.timeSlot, style = MaterialTheme.typography.bodyLarge)

                        val counter = stringResource(R.string.loading_fleteros, offer.fleteros, FleteroQuota.LIMIT)
                        Text(
                            // Marked and not withheld, the way the grid's own counter marks a window past
                            // the limit: the server accepts a fourth fletero by an explicit business
                            // decision, so the option stays selectable and this mark is information
                            // rather than a gate.
                            text = if (offer.exceeded) "$counter ⚠" else counter,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (offer.exceeded) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // Whatever the state has to say, as it carries it: the window's refusal is the server's
            // sentence, and the sentences a failed read leaves are the loading screen's own.
            state.message?.let { message ->
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            // The action, withheld whole on a row the server closed -- see this screen's comment for why
            // it is not drawn disabled instead. Where it is drawn it is exactly as live as the state says
            // and no more: `canSubmit` is the view model's own answer, and this screen does not
            // second-guess it by comparing the selection with the slot it arrived carrying.
            if (!state.readOnly) {
                Button(
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    if (state.submitting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.loading_edit_save))
                    }
                }
            }
        }
    }
}

/**
 * The screen with its ViewModel attached.
 *
 * The load is keyed on the row *and the day*, because the day is half of the read: there is no
 * single-entry route, so the row is found inside the day it was opened from, and a day this screen was
 * not told about is a row it cannot look for.
 *
 * The view model is keyed on the session, the way the dashboard's is and for the reason its comment
 * gives: it lives in the activity's store, so an instance built for a previous session would be the very
 * one this screen draws, with that session's row and that session's selection retained. Keyed to the
 * session, the instance this route draws is new, and a new one starts at `loading = true` -- which is
 * the spinner above rather than somebody else's row.
 *
 * The save is reported by waiting for it rather than by keying an effect on the flag, and the reason is
 * that same scope: a `saved` flag left true by a previous visit would be read by the first composition
 * of the next one, and an effect keyed on it would close the screen before the load this arrival just
 * asked for ever landed. Waiting on the flow is what makes "a save" mean the write *this* arrival sent --
 * `load` clears the flag for the same reason -- and it needs no assumption about the order two effects
 * start in.
 */
@Composable
fun LoadingEditRoute(
    scheduleId: Int,
    date: String,
    sessionKey: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: LoadingEditViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(scheduleId, date) {
        viewModel.load(scheduleId, date)
        viewModel.state.first { it.saved }
        onSaved()
    }

    LoadingEditScreen(
        state = state,
        onTimeSlotChange = viewModel::onTimeSlotChange,
        onSubmit = viewModel::submit,
        onBack = onBack,
    )
}
