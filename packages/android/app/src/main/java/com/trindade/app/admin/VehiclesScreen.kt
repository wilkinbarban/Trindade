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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trindade.app.contract.models.AdminVehicleResponseVehicle
import com.trindade.app.ui.components.AdminDestructiveButton
import com.trindade.app.ui.components.AdminDestructiveConfirmButton
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton

/** Vehicle catalog UI. Accepts ViewModel state and callbacks directly; role gating fails closed for non-admin. */
@Composable
fun VehiclesScreen(
    state: VehiclesViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onDescriptionChange: (String) -> Unit = {},
    onLicensePlateChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    onEdit: (AdminVehicleResponseVehicle) -> Unit = {},
    onToggle: (AdminVehicleResponseVehicle) -> Unit = {},
    onRequestDelete: (AdminVehicleResponseVehicle) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showForm by remember { mutableStateOf(false) }
    var localDeleteTarget by remember { mutableStateOf<AdminVehicleResponseVehicle?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack) { Text("Voltar") }
            Text("Veículos", style = MaterialTheme.typography.titleLarge)
            NavigationActionButton(
                onClick = onRefresh,
                enabled = !state.loading && !state.saving,
            ) {
                Text("Atualizar")
            }
        }

        if (state.loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text("Carregando…")
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.refusedStatus?.let { Text("Status: $it", color = MaterialTheme.colorScheme.error) }

        if (state.isAdmin) {
            AdminPrimaryButton(
                onClick = { showForm = true },
                enabled = !state.saving,
                text = "Novo veículo",
            )
        }

        if (state.isAdmin && (showForm || state.editingId != null)) {
            OutlinedTextField(
                value = state.description,
                onValueChange = onDescriptionChange,
                label = { Text("Descrição") },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.licensePlate,
                onValueChange = onLicensePlateChange,
                label = { Text("Placa") },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = onSave,
                    enabled = !state.saving && !state.loading,
                    loading = state.saving,
                    text = if (state.saving) "Salvando…" else if (state.editingId == null) "Criar" else "Salvar",
                )
                AdminSecondaryButton(
                    onClick = {
                        showForm = false
                        onCancel()
                    },
                    enabled = !state.saving,
                    text = "Cancelar",
                )
            }
        }

        state.vehicles.forEach { vehicle ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(vehicle.description, style = MaterialTheme.typography.titleMedium)
                    Text(vehicle.licensePlate)
                    Text(if (vehicle.isActive == 1) "Ativo" else "Inativo")

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (state.canEdit(vehicle)) {
                            AdminSecondaryButton(onClick = { onEdit(vehicle) }, text = "Editar")
                        } else {
                            Text("Somente leitura", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canToggle(vehicle)) {
                            AdminSecondaryButton(
                                onClick = { onToggle(vehicle) },
                                text = if (vehicle.isActive == 1) "Desativar" else "Ativar",
                            )
                        }
                        if (state.canDelete(vehicle)) {
                            AdminDestructiveButton(
                                onClick = {
                                    onRequestDelete(vehicle)
                                    localDeleteTarget = vehicle
                                },
                                enabled = !state.saving,
                                text = "Excluir",
                            )
                        }
                    }
                }
            }
        }

        if (!state.loading && state.vehicles.isEmpty()) {
            Text("Nenhum veículo")
        }
    }

    val targetToDelete = state.deleteTarget ?: localDeleteTarget
    targetToDelete?.let { vehicle ->
        if (state.canDelete(vehicle)) {
            AlertDialog(
                onDismissRequest = {
                    localDeleteTarget = null
                    onDismissDelete()
                },
                title = { Text("Confirmar exclusão") },
                text = { Text("Excluir ${vehicle.description}?") },
                confirmButton = {
                    AdminDestructiveConfirmButton(
                        onClick = {
                            localDeleteTarget = null
                            onConfirmDelete()
                        },
                        text = "Confirmar",
                    )
                },
                dismissButton = {
                    AdminSecondaryButton(
                        onClick = {
                            localDeleteTarget = null
                            onDismissDelete()
                        },
                        text = "Cancelar",
                    )
                },
            )
        }
    }
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
        onRefresh = viewModel::load,
        onDescriptionChange = viewModel::onDescriptionChange,
        onLicensePlateChange = viewModel::onLicensePlateChange,
        onSave = viewModel::save,
        onCancel = viewModel::cancelEdit,
        onEdit = viewModel::edit,
        onToggle = viewModel::toggle,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::cancelDelete,
        modifier = modifier,
    )
}
