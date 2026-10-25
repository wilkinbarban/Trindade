package com.trindade.app.admin

import android.app.TimePickerDialog
import android.content.Context
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.ui.components.AdminConfirmDialog
import com.trindade.app.ui.components.AdminConfirmation
import com.trindade.app.ui.components.AdminDestructiveButton
import com.trindade.app.ui.components.AdminDestructiveConfirmButton
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminResultDialog
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton
import java.util.Locale
import com.trindade.app.ui.resolve

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
    onDismissOutcome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingConfirmation by remember { mutableStateOf<AdminConfirmation?>(null) }
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
                text = message.resolve(),
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
                onSave = {
                    val slotInput = state.form.slotInput
                    pendingConfirmation = AdminConfirmation(
                        title = context.getString(R.string.admin_action_create),
                        message = context.getString(R.string.admin_dialog_create_body, slotInput),
                        confirmLabel = context.getString(R.string.admin_action_confirm),
                        onConfirm = {
                            pendingConfirmation = null
                            onSave()
                        },
                    )
                },
                onCancel = onCancelForm,
            )
        } else {
            if (state.isAdmin) {
                AdminPrimaryButton(
                    onClick = onOpenCreate,
                    modifier = Modifier.fillMaxWidth(),
                    text = stringResource(R.string.time_slots_add_new),
                )
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
    val deleteConfirmation = slotToDelete?.let { slot ->
        AdminConfirmation(
            title = stringResource(R.string.admin_action_delete),
            message = stringResource(R.string.admin_dialog_delete_body, slot),
            confirmLabel = stringResource(R.string.admin_action_delete),
            isDestructive = true,
            onConfirm = onConfirmDelete,
        )
    }

    AdminConfirmDialog(
        confirmation = deleteConfirmation ?: pendingConfirmation,
        onDismiss = {
            if (deleteConfirmation != null) {
                onCancelDelete()
            } else {
                pendingConfirmation = null
            }
        },
    )

    AdminResultDialog(
        outcome = state.outcome,
        onClose = onDismissOutcome,
    )
}

@Composable
private fun TimeSlotRow(
    slot: String,
    isAdmin: Boolean,
    onRequestDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
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
                AdminDestructiveButton(
                    onClick = onRequestDelete,
                    text = stringResource(R.string.time_slots_remove),
                )
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
    val context = LocalContext.current

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
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
                // The mask, not the raw keystroke: four digits with the colon placed by the field. The
                // ViewModel still validates the result, because a mask says what a time looks like and not
                // whether 25:00 is one.
                onValueChange = { onSlotInputChanged(formatTimeSlotMask(it)) },
                label = { Text(stringResource(R.string.time_slots_slot_label)) },
                placeholder = { Text(stringResource(R.string.time_slots_slot_placeholder)) },
                isError = form.validationError != null,
                supportingText = form.validationError?.let { err ->
                    { Text(err.resolve(), color = MaterialTheme.colorScheme.error) }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailingIcon = {
                    // The picker for the operators who would rather point at the hour than count digits.
                    // It writes through the same callback as the field, so the form keeps one source of
                    // truth for what was chosen.
                    IconButton(
                        onClick = { openTimePicker(context, form.slotInput, onSlotInputChanged) },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file_clock),
                            contentDescription = stringResource(R.string.time_slots_pick_time),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = onSave,
                    enabled = form.canSubmit,
                    loading = form.saving,
                    text = stringResource(R.string.time_slots_add),
                )
                AdminSecondaryButton(
                    onClick = onCancel,
                    enabled = !form.saving,
                    text = stringResource(R.string.time_slots_cancel),
                )
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
        onDismissOutcome = viewModel::dismissOutcome,
        modifier = modifier,
    )
}

/**
 * Opens the platform's hour picker seeded with what the field already holds.
 *
 * The seeded value is read leniently: a half-typed `14:3` seeds 14:03 rather than refusing to open, because
 * the operator asking for the picker is usually the one who gave up on typing. The answer is written back in
 * `HH:mm`, which is the shape the ViewModel validates.
 */
private fun openTimePicker(context: Context, current: String, onPick: (String) -> Unit) {
    val parts = current.split(":")
    val hour = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, 23) ?: 8
    val minute = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(0, 59) ?: 0

    TimePickerDialog(
        context,
        { _, pickedHour, pickedMinute ->
            onPick(String.format(Locale.ROOT, "%02d:%02d", pickedHour, pickedMinute))
        },
        hour,
        minute,
        true,
    ).show()
}
