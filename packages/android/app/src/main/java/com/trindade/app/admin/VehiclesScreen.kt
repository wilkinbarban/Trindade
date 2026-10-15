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
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.ui.components.NavigationActionButton

@Composable
fun VehiclesScreen(
    state: VehiclesViewModel.UiState,
    onBack: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenEdit: (AdminVehicleResponseVehicle) -> Unit,
    onDescriptionChanged: (String) -> Unit,
    onLicensePlateChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancelForm: () -> Unit,
    onToggleActive: (AdminVehicleResponseVehicle) -> Unit,
    onRequestDelete: (AdminVehicleResponseVehicle) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val handleBack = {
        when {
            state.deleteConfirmVehicle != null -> onCancelDelete()
            state.form != null -> onCancelForm()
            else -> onBack()
        }
    }

    BackHandler(enabled = state.deleteConfirmVehicle != null || state.form != null) {
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
            Text(stringResource(R.string.vehicles_title), style = MaterialTheme.typography.titleLarge)
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
            VehicleForm(
                state = state,
                onDescriptionChanged = onDescriptionChanged,
                onLicensePlateChanged = onLicensePlateChanged,
                onSave = onSave,
                onCancel = onCancelForm,
            )
        } else {
            if (state.isAdmin) {
                Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.vehicles_add_new))
                }
            }
            if (state.vehicles.isEmpty()) {
                Text(stringResource(R.string.vehicles_empty))
            } else {
                state.vehicles.forEach { vehicle ->
                    VehicleRow(
                        vehicle = vehicle,
                        state = state,
                        onEdit = onOpenEdit,
                        onToggleActive = onToggleActive,
                        onDelete = onRequestDelete,
                    )
                }
            }
        }
    }

    state.deleteConfirmVehicle?.let { vehicle ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.vehicles_delete_confirm_title)) },
            text = { Text(stringResource(R.string.vehicles_delete_confirm_body, vehicle.description)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.deleting) {
                    if (state.deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.vehicles_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.deleting) {
                    Text(stringResource(R.string.vehicles_cancel))
                }
            },
        )
    }
}

@Composable
private fun VehicleRow(
    vehicle: AdminVehicleResponseVehicle,
    state: VehiclesViewModel.UiState,
    onEdit: (AdminVehicleResponseVehicle) -> Unit,
    onToggleActive: (AdminVehicleResponseVehicle) -> Unit,
    onDelete: (AdminVehicleResponseVehicle) -> Unit,
) {
    val toggleDescription = stringResource(
        if (vehicle.isActive == 1) R.string.vehicles_deactivate else R.string.vehicles_activate,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(vehicle.description, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.vehicles_plate_value, vehicle.licensePlate))
            Text(
                stringResource(
                    if (vehicle.isActive == 1) R.string.vehicles_status_active else R.string.vehicles_status_inactive,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canToggleActive(vehicle)) {
                    Switch(
                        checked = vehicle.isActive == 1,
                        onCheckedChange = { onToggleActive(vehicle) },
                        modifier = Modifier.semantics {
                            contentDescription = toggleDescription
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (state.canEdit(vehicle)) {
                    TextButton(onClick = { onEdit(vehicle) }) {
                        Text(stringResource(R.string.vehicles_edit))
                    }
                }
                if (state.canDelete(vehicle)) {
                    TextButton(onClick = { onDelete(vehicle) }) {
                        Text(stringResource(R.string.vehicles_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun VehicleForm(
    state: VehiclesViewModel.UiState,
    onDescriptionChanged: (String) -> Unit,
    onLicensePlateChanged: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form ?: return

    Text(
        text = stringResource(if (form.isEditing) R.string.vehicles_edit_title else R.string.vehicles_create_title),
        style = MaterialTheme.typography.titleMedium,
    )

    OutlinedTextField(
        value = form.description,
        onValueChange = onDescriptionChanged,
        label = { Text(stringResource(R.string.vehicles_description)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.licensePlate,
        onValueChange = onLicensePlateChanged,
        label = { Text(stringResource(R.string.vehicles_plate)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = form.canSubmit) {
            if (form.saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (form.isEditing) R.string.vehicles_update else R.string.vehicles_create))
            }
        }
        TextButton(onClick = onCancel, enabled = !form.saving) {
            Text(stringResource(R.string.vehicles_cancel))
        }
    }
}

@Composable
fun VehiclesRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: VehiclesViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadData() }

    BackHandler(enabled = state.deleteConfirmVehicle != null || state.form != null) {
        viewModel.handleBack()
    }

    VehiclesScreen(
        state = state,
        onBack = {
            if (!viewModel.handleBack()) {
                onBack()
            }
        },
        onOpenCreate = viewModel::openCreateForm,
        onOpenEdit = viewModel::openEditForm,
        onDescriptionChanged = viewModel::onDescriptionChanged,
        onLicensePlateChanged = viewModel::onLicensePlateChanged,
        onSave = viewModel::saveVehicle,
        onCancelForm = viewModel::closeForm,
        onToggleActive = viewModel::toggleVehicleActive,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        modifier = modifier,
    )
}
