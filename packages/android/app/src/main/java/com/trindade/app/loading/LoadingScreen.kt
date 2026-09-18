package com.trindade.app.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
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
 */
@Composable
fun LoadingScreen(
    state: LoadingViewModel.UiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
        }

        item {
            Text(text = stringResource(R.string.loading_title), style = MaterialTheme.typography.titleLarge)
            Text(text = state.date, style = MaterialTheme.typography.bodyMedium)
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

        items(state.slots) { slot ->
            SlotBlock(
                slot = slot,
                entries = state.entriesIn(slot),
                fleteroCount = state.fleteroCountIn(slot),
                exceeded = state.isExceeded(slot),
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
            Text(
                text = describe(entry),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        HorizontalDivider()
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

    LoadingScreen(state = state, onBack = onBack)
}
