package com.trindade.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.resolve

/** Time-slot catalog UI. Consumes ViewModel state and callbacks directly; role gating fails closed for non-admin. */
@Composable
fun TimeSlotsScreen(
    state: TimeSlotsViewModel.UiState,
    onBack: () -> Unit,
    onInputChange: (String) -> Unit = {},
    onAddTimeSlot: () -> Unit = {},
    onRemoveTimeSlot: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack) { Text(stringResource(R.string.admin_back)) }
            Text(stringResource(R.string.admin_time_slots_title), style = MaterialTheme.typography.titleLarge)
        }

        if (state.loading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.admin_loading))
            }
        }

        state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }
        state.refusedStatus?.let {
            Text(
                stringResource(R.string.admin_time_slots_refused_status, it),
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (state.canAdd()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = state.input,
                    onValueChange = onInputChange,
                    label = { Text(stringResource(R.string.admin_time_slots_new)) },
                    placeholder = { Text("HH:MM") },
                    enabled = !state.saving,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Button(
                    onClick = onAddTimeSlot,
                    enabled = !state.saving && !state.loading,
                ) {
                    if (state.saving) {
                        CircularProgressIndicator()
                        Text(stringResource(R.string.admin_action_saving))
                    } else {
                        Text(stringResource(R.string.admin_time_slots_add))
                    }
                }
            }
        }

        state.timeSlots.forEach { slot ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(slot, style = MaterialTheme.typography.titleMedium)
                    if (state.canRemove(slot)) {
                        TextButton(
                            onClick = { onRemoveTimeSlot(slot) },
                            enabled = !state.saving,
                        ) {
                            Text(stringResource(R.string.admin_time_slots_remove))
                        }
                    } else {
                        Text(
                            stringResource(R.string.admin_read_only),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        if (!state.loading && state.timeSlots.isEmpty()) {
            Text(stringResource(R.string.admin_time_slots_empty))
        }
    }
}
