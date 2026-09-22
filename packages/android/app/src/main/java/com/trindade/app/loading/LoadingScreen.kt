package com.trindade.app.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner

/**
 * The loading schedule for one day.
 *
 * Every slot of the day is drawn, including the empty ones, because the grid is what tells an operator
 * where an entry could go; the configured slots come from the server rather than from a list here, so a
 * slot added in settings shows up without a client change.
 *
 * The counter next to each slot is the three-fletero indication. It is a display: the server accepts a
 * fourth, so it never withholds anything, and when the window is exceeded the slot says so rather than
 * refusing.
 *
 * The export lives with the day's header rather than at the end of the grid. The grid is long enough to
 * scroll, and the action is about the whole day, so it would otherwise sit behind every slot it
 * describes.
 */
@Composable
fun LoadingScreen(
    state: LoadingViewModel.UiState,
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onStartAdding: (String) -> Unit,
    onCancelAdding: () -> Unit,
    onDriverSelected: (Int) -> Unit,
    onVehicleSelected: (Int) -> Unit,
    onConfirmAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onEditEntry: (Int, String) -> Unit,
    onLoadExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
        }

        item {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = stringResource(R.string.loading_title), style = MaterialTheme.typography.titleLarge)
                    // The history lives on this screen's header rather than in a third tab, and that is
                    // the deliberate part of the placement: the history lists the batches this grid
                    // produces, so the way to them is an action on the surface that makes them. The tab
                    // row stays the app's two jobs, the same choice the report history made on the
                    // generator.
                    TextButton(onClick = onOpenHistory) { Text(stringResource(R.string.report_history)) }
                }
                Text(text = state.date, style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(
                    onClick = onLoadExport,
                    enabled = !state.loadingExport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.loadingExport) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.export_load))
                    }
                }
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

        // The server's own text, shown verbatim and copied as it stands. It is the product's output, so
        // there is nothing here to assemble and nothing to reformat for a narrower screen.
        state.exportText?.let { text ->
            item {
                Text(text = text, style = MaterialTheme.typography.bodyMedium)
                Button(
                    onClick = { clipboard.setText(AnnotatedString(text)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.export_copy))
                }
            }
        }

        items(state.slots) { slot ->
            SlotBlock(
                slot = slot,
                entries = state.entriesIn(slot),
                fleteroCount = state.fleteroCountIn(slot),
                exceeded = state.isExceeded(slot),
                state = state,
                onStartAdding = onStartAdding,
                onCancelAdding = onCancelAdding,
                onDriverSelected = onDriverSelected,
                onVehicleSelected = onVehicleSelected,
                onConfirmAdd = onConfirmAdd,
                onDelete = onDelete,
                onEditEntry = onEditEntry,
            )
        }
    }
}

@Composable
private fun SlotBlock(
    slot: String,
    entries: List<SchedulesResponseSchedulesInner>,
    fleteroCount: Int,
    exceeded: Boolean,
    state: LoadingViewModel.UiState,
    onStartAdding: (String) -> Unit,
    onCancelAdding: () -> Unit,
    onDriverSelected: (Int) -> Unit,
    onVehicleSelected: (Int) -> Unit,
    onConfirmAdd: () -> Unit,
    onDelete: (Int) -> Unit,
    onEditEntry: (Int, String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(text = slot, style = MaterialTheme.typography.titleMedium)

            val counter = stringResource(R.string.loading_fleteros, fleteroCount, FleteroQuota.LIMIT)
            Text(
                text = if (exceeded) "$counter ⚠" else counter,
                style = MaterialTheme.typography.bodyMedium,
                // Coloured rather than withheld: the limit is informative, so the slot still accepts
                // another driver and the operator is told the effect instead of being stopped by it.
                color = if (exceeded) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        entries.forEach { entry ->
            // The three children of this row are its identity and its two actions, in that order and as
            // siblings of one `Row`, rather than the identity and a nested block: `weight` on the
            // identity is what keeps each action's own `Text` and the row it belongs to in one layout, so
            // what the operator sees beside a row is the action for that row and not for the one above it.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = describe(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                // The way in, offered only where the server's own flags allow it. `isReadOnly()` is the
                // predicate the edit screen reads too -- one function in this package, so a row this grid
                // offers an edit for is never one that screen would draw with no save at all. A row the
                // server closed draws no action here rather than a disabled one, which is the reports'
                // rule: an action that is drawn but refuses is still an offer. The day travels with the id
                // because that screen reads the row out of its day (`GET /api/loading/schedules?date=`),
                // and the day this grid is showing is written down here and nowhere else.
                if (!entry.isReadOnly()) {
                    TextButton(onClick = { onEditEntry(entry.id, state.date) }) {
                        Text(stringResource(R.string.report_edit))
                    }
                }
                TextButton(onClick = { onDelete(entry.id) }, enabled = !state.busy) {
                    Text(stringResource(R.string.report_photo_remove))
                }
            }
        }

        if (state.addingToSlot == slot) {
            AddEntryForm(
                state = state,
                onDriverSelected = onDriverSelected,
                onVehicleSelected = onVehicleSelected,
                onConfirm = onConfirmAdd,
                onCancel = onCancelAdding,
            )
        } else {
            // Offered even when the window is exceeded. The limit is a display, and withholding this
            // would turn it into a rule the server does not enforce.
            TextButton(onClick = { onStartAdding(slot) }, enabled = !state.busy) {
                Text(stringResource(R.string.loading_add_entry))
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun AddEntryForm(
    state: LoadingViewModel.UiState,
    onDriverSelected: (Int) -> Unit,
    onVehicleSelected: (Int) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp)) {
        Text(text = stringResource(R.string.loading_pick_driver), style = MaterialTheme.typography.labelLarge)

        state.drivers.forEach { driver ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = state.selectedDriverId == driver.id,
                    onClick = { onDriverSelected(driver.id) },
                )
                Text(text = driver.name, style = MaterialTheme.typography.bodyMedium)
            }
        }

        // Only for a company driver: an external one brings their own vehicle, and the server rejects
        // a casa assignment without one.
        if (state.needsVehicle) {
            Text(text = stringResource(R.string.loading_pick_vehicle), style = MaterialTheme.typography.labelLarge)
            state.vehicles.forEach { vehicle ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = state.selectedVehicleId == vehicle.id,
                        onClick = { onVehicleSelected(vehicle.id) },
                    )
                    Text(
                        text = "${vehicle.description} · ${vehicle.licensePlate}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onConfirm, enabled = state.canConfirm) {
                Text(stringResource(R.string.loading_confirm))
            }
            TextButton(onClick = onCancel) { Text(stringResource(R.string.loading_cancel)) }
        }
    }
}

/**
 * How an entry reads in the grid.
 *
 * A company driver is shown with the vehicle because that pairing is what identifies the row to an
 * operator; an external one is shown by name and plate, since the vehicle is theirs.
 *
 * Not private, because the edit screen draws the row it is editing with this same line: the row the
 * operator tapped and the row the next screen shows them are the same row, and two expressions for it
 * would be two answers to what an entry is called -- the drift a second copy eventually grows.
 */
internal fun describe(entry: SchedulesResponseSchedulesInner): String {
    val name = entry.driverName ?: entry.licensePlate ?: "—"
    val vehicle = entry.vehicleDescription
    val plate = entry.vehiclePlate ?: entry.licensePlate

    return listOfNotNull(name, vehicle, plate).joinToString(" · ")
}

@Composable
fun LoadingRoute(
    onBack: () -> Unit,
    onOpenHistory: () -> Unit,
    onEditEntry: (Int, String) -> Unit,
    date: String? = null,
    stale: Boolean = false,
    onStaleRead: () -> Unit = {},
    viewModel: LoadingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    /**
     * The grid's day, asked for through the view model's own `onDateChange` -- the method that has
     * existed since D4b with no caller, and this slice is where the history gives it one.
     *
     * With a date, that day is loaded, and the read is forced because a day the operator asked for is
     * what asking is for. The view model's constructor has already started a load of today by the time
     * this runs, so the two are briefly in the air together, and what makes the requested day win is
     * the request token inside the view model rather than an ordering guaranteed here.
     *
     * Without a date this is the loading tab, which means today -- and today is the one day the arrival
     * does not have to read, because the constructor's load is already that read; the return trip from
     * the history's day is covered by the arrival naming a different day, which is read.
     *
     * The one thing an arrival cannot decide for itself is a write. The editor that moves a row is
     * another view model on another copy of the day, so a save reaches this side of the app only as the
     * overlay flag `MainActivity` clears -- and nothing of it reaches the grid, which is then drawn
     * again on the day it was already showing with the day as it was *before* the edit: the row the
     * operator moved would come back in the slot they moved it out of. [stale] is that fact travelling
     * with the arrival, the read below is forced for it, and `onStaleRead` gives it back -- one save
     * costs the grid one read, and no later arrival pays for it again.
     *
     * Keyed on the day alone, and deliberately: taking the fact flips [stale] back to false in the same
     * composition, so an effect keyed on it would run a second time for a save the read already
     * answered.
     */
    LaunchedEffect(date) {
        viewModel.onDateChange(date ?: LoadingViewModel.saoPauloToday(), force = date != null || stale)
        if (stale) onStaleRead()
    }

    LoadingScreen(
        state = state,
        onBack = onBack,
        onOpenHistory = onOpenHistory,
        onStartAdding = viewModel::startAdding,
        onCancelAdding = viewModel::cancelAdding,
        onDriverSelected = viewModel::onDriverSelected,
        onVehicleSelected = viewModel::onVehicleSelected,
        onConfirmAdd = viewModel::confirmAdd,
        onDelete = viewModel::deleteEntry,
        onEditEntry = onEditEntry,
        onLoadExport = viewModel::loadExport,
    )
}
