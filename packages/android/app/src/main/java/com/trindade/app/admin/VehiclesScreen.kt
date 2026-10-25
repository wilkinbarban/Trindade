package com.trindade.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.ui.components.AdminConfirmDialog
import com.trindade.app.ui.components.AdminConfirmation
import com.trindade.app.ui.components.AdminDestructiveButton
import com.trindade.app.ui.components.AdminDestructiveConfirmButton
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminResultDialog
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.resolve

/** Vehicle catalog UI. Accepts ViewModel state and callbacks directly; role gating fails closed for non-admin. */
@Composable
fun VehiclesScreen(
    state: VehiclesViewModel.UiState,
    onBack: () -> Unit,
    onDescriptionChange: (String) -> Unit = {},
    onLicensePlateChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    onEdit: (AdminVehicleResponseVehicle) -> Unit = {},
    onToggle: (AdminVehicleResponseVehicle) -> Unit = {},
    onRequestDelete: (AdminVehicleResponseVehicle) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onDismissOutcome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showForm by remember { mutableStateOf(false) }
    var localDeleteTarget by remember { mutableStateOf<AdminVehicleResponseVehicle?>(null) }
    var pendingConfirmation by remember { mutableStateOf<AdminConfirmation?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack) { Text(stringResource(R.string.admin_back)) }
            Text(stringResource(R.string.admin_section_vehicles), style = MaterialTheme.typography.titleLarge)
        }

        if (state.loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.admin_loading))
            }
        }

        state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }

        if (state.isAdmin) {
            AdminPrimaryButton(
                onClick = { showForm = true },
                enabled = !state.saving,
                text = stringResource(R.string.admin_new_vehicle),
            )
        }

        if (state.isAdmin && (showForm || state.editingId != null)) {
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text(stringResource(R.string.admin_field_description)) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.licensePlate,
                onValueChange = onLicensePlateChange,
                label = { Text(stringResource(R.string.admin_field_plate)) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = {
                        val isCreate = state.editingId == null
                        val titleRes = if (isCreate) R.string.admin_action_create else R.string.admin_action_save
                        val bodyRes = if (isCreate) R.string.admin_dialog_create_body else R.string.admin_dialog_save_body
                        val vehicleName = state.description.ifBlank { state.licensePlate }
                        pendingConfirmation = AdminConfirmation(
                            title = context.getString(titleRes),
                            message = context.getString(bodyRes, vehicleName),
                            confirmLabel = context.getString(R.string.admin_action_confirm),
                            onConfirm = {
                                pendingConfirmation = null
                                onSave()
                            },
                        )
                    },
                    enabled = !state.saving && !state.loading,
                    loading = state.saving,
                    text = if (state.saving) stringResource(R.string.admin_action_saving) else if (state.editingId == null) stringResource(R.string.admin_action_create) else stringResource(R.string.admin_action_save),
                )
                AdminSecondaryButton(
                    onClick = {
                        showForm = false
                        onCancel()
                    },
                    enabled = !state.saving,
                    text = stringResource(R.string.admin_action_cancel),
                )
            }
        }

        state.vehicles.forEach { vehicle ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(vehicle.description, style = MaterialTheme.typography.titleMedium)
                    Text(vehicle.licensePlate)
                    Text(if (vehicle.isActive == 1) stringResource(R.string.admin_status_active) else stringResource(R.string.admin_status_inactive))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (state.canEdit(vehicle)) {
                            AdminSecondaryButton(onClick = { onEdit(vehicle) }, text = stringResource(R.string.admin_action_edit))
                        } else {
                            Text(stringResource(R.string.admin_read_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canToggle(vehicle)) {
                            AdminSecondaryButton(
                                onClick = {
                                    val isActivating = vehicle.isActive != 1
                                    val titleRes = if (isActivating) R.string.admin_action_activate else R.string.admin_action_deactivate
                                    val bodyRes = if (isActivating) R.string.admin_dialog_toggle_on_body else R.string.admin_dialog_toggle_off_body
                                    val vehicleName = vehicle.description.ifBlank { vehicle.licensePlate }
                                    pendingConfirmation = AdminConfirmation(
                                        title = context.getString(titleRes),
                                        message = context.getString(bodyRes, vehicleName),
                                        confirmLabel = context.getString(R.string.admin_action_confirm),
                                        onConfirm = {
                                            pendingConfirmation = null
                                            onToggle(vehicle)
                                        },
                                    )
                                },
                                text = if (vehicle.isActive == 1) stringResource(R.string.admin_action_deactivate) else stringResource(R.string.admin_action_activate),
                            )
                        }
                        if (state.canDelete(vehicle)) {
                            AdminDestructiveButton(
                                onClick = {
                                    onRequestDelete(vehicle)
                                    localDeleteTarget = vehicle
                                },
                                enabled = !state.saving,
                                text = stringResource(R.string.admin_action_delete),
                            )
                        }
                    }
                }
            }
        }

        if (!state.loading && state.vehicles.isEmpty()) {
            Text(stringResource(R.string.admin_no_vehicles))
        }
    }

    val targetToDelete = (state.deleteTarget ?: localDeleteTarget)?.takeIf { state.canDelete(it) }
    val deleteConfirmation = targetToDelete?.let { vehicle ->
        val vehicleName = vehicle.description.ifBlank { vehicle.licensePlate }
        AdminConfirmation(
            title = stringResource(R.string.admin_action_delete),
            message = stringResource(R.string.admin_dialog_delete_body, vehicleName),
            confirmLabel = stringResource(R.string.admin_action_delete),
            isDestructive = true,
            onConfirm = {
                localDeleteTarget = null
                onConfirmDelete()
            },
        )
    }

    AdminConfirmDialog(
        confirmation = deleteConfirmation ?: pendingConfirmation,
        onDismiss = {
            if (deleteConfirmation != null) {
                localDeleteTarget = null
                onDismissDelete()
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
fun VehiclesRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VehiclesViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = sessionKey),
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    VehiclesScreen(
        state = state,
        onBack = onBack,
        onDescriptionChange = viewModel::onDescriptionChange,
        onLicensePlateChange = viewModel::onLicensePlateChange,
        onSave = viewModel::save,
        onCancel = viewModel::cancelEdit,
        onEdit = viewModel::edit,
        onToggle = viewModel::toggle,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::cancelDelete,
        onDismissOutcome = viewModel::dismissOutcome,
        modifier = modifier,
    )
}
