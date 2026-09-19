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
    onStartAdding: (String) -> Unit,
    onCancelAdding: () -> Unit,
    onDriverSelected: (Int) -> Unit,
    onVehicleSelected: (Int) -> Unit,
    onConfirmAdd: () -> Unit,
    onDelete: (Int) -> Unit,
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
                Text(text = stringResource(R.string.loading_title), style = MaterialTheme.typography.titleLarge)
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = describe(entry),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(start = 8.dp),
                )
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
 */
private fun describe(entry: SchedulesResponseSchedulesInner): String {
    val name = entry.driverName ?: entry.licensePlate ?: "—"
    val vehicle = entry.vehicleDescription
    val plate = entry.vehiclePlate ?: entry.licensePlate

    return listOfNotNull(name, vehicle, plate).joinToString(" · ")
}

@Composable
fun LoadingRoute(
    onBack: () -> Unit,
    viewModel: LoadingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    LoadingScreen(
        state = state,
        onBack = onBack,
        onStartAdding = viewModel::startAdding,
        onCancelAdding = viewModel::cancelAdding,
        onDriverSelected = viewModel::onDriverSelected,
        onVehicleSelected = viewModel::onVehicleSelected,
        onConfirmAdd = viewModel::confirmAdd,
        onDelete = viewModel::deleteEntry,
        onLoadExport = viewModel::loadExport,
    )
}
