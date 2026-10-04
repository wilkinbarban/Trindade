package com.trindade.app.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.ui.components.NavigationActionButton

@Composable
fun TimeSlotsAdminScreen(
    state: TimeSlotsAdminViewModel.UiState,
    onBack: () -> Unit,
    onOpenCreate: () -> Unit,
    onSlotInputChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancelForm: () -> Unit,
    onRequestDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val handleBack = {
        when {
            state.deleting || state.form?.saving == true -> Unit
            state.deleteConfirmSlot != null -> onCancelDelete()
            state.form != null -> onCancelForm()
            else -> onBack()
        }
    }

    BackHandler {
        handleBack()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavigationActionButton(onClick = handleBack) {
                Text(stringResource(R.string.report_back))
            }
            Text(stringResource(R.string.time_slots_title), style = MaterialTheme.typography.titleLarge)
        }

        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
            return@Column
        }

        state.message?.let { message ->
            Text(
                text = message,
                color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    liveRegion = LiveRegionMode.Polite
                },
            )
        }

        if (state.form != null) {
            TimeSlotForm(
                state = state,
                onSlotInputChanged = onSlotInputChanged,
                onSave = onSave,
                onCancel = onCancelForm,
            )
        } else {
            if (state.isAdmin) {
                Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.time_slots_add_new))
                }
            }
            if (state.timeSlots.isEmpty()) {
                Text(stringResource(R.string.time_slots_empty))
            } else {
                state.timeSlots.forEach { slot ->
                    TimeSlotRow(
                        slot = slot,
                        isAdmin = state.isAdmin,
                        onRequestDelete = { onRequestDelete(slot) },
                    )
                }
            }
        }
    }

    val slotToDelete = state.deleteConfirmSlot
    if (slotToDelete != null) {
        AlertDialog(
            onDismissRequest = onCancelDelete,
            properties = if (state.deleting) {
                DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
            } else {
                DialogProperties()
            },
            title = { Text(stringResource(R.string.time_slots_delete_confirm_title)) },
            text = { Text(stringResource(R.string.time_slots_delete_confirm_body, slotToDelete)) },
            confirmButton = {
                Button(
                    onClick = onConfirmDelete,
                    enabled = !state.deleting,
                ) {
                    if (state.deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.time_slots_remove))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.deleting) {
                    Text(stringResource(R.string.time_slots_cancel))
                }
            },
        )
    }
}

@Composable
private fun TimeSlotRow(
    slot: String,
    isAdmin: Boolean,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = slot,
                style = MaterialTheme.typography.titleMedium,
            )
            if (isAdmin) {
                Button(onClick = onRequestDelete) {
                    Text(stringResource(R.string.time_slots_remove))
                }
            }
        }
    }
}

@Composable
private fun TimeSlotForm(
    state: TimeSlotsAdminViewModel.UiState,
    onSlotInputChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val form = state.form ?: return

    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.time_slots_new_slot),
                style = MaterialTheme.typography.titleMedium,
            )

            OutlinedTextField(
                value = form.slotInput,
                onValueChange = onSlotInputChanged,
                label = { Text(stringResource(R.string.time_slots_slot_label)) },
                placeholder = { Text(stringResource(R.string.time_slots_slot_placeholder)) },
                isError = form.validationError != null,
                supportingText = form.validationError?.let { err ->
                    { Text(err, color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = form.canSubmit) {
                    if (form.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.time_slots_add))
                    }
                }
                TextButton(onClick = onCancel, enabled = !form.saving) {
                    Text(stringResource(R.string.time_slots_cancel))
                }
            }
        }
    }
}

@Composable
fun TimeSlotsAdminRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimeSlotsAdminViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadData() }

    TimeSlotsAdminScreen(
        state = state,
        onBack = {
            if (!viewModel.handleBack()) {
                onBack()
            }
        },
        onOpenCreate = viewModel::openAddForm,
        onSlotInputChanged = viewModel::onSlotInputChanged,
        onSave = viewModel::addTimeSlot,
        onCancelForm = viewModel::closeForm,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        modifier = modifier,
    )
}
