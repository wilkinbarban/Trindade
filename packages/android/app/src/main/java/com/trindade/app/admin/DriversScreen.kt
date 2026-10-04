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
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.ui.components.AdminChoiceChip
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton

/** Driver catalog UI. The ViewModel state supplies role and ownership policy. */
@Composable
fun DriversScreen(
    state: DriversViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit,
    onLicensePlateChange: (String) -> Unit,
    onDriverTypeChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onEdit: (AdminDriverResponseDriver) -> Unit,
    onToggle: (AdminDriverResponseDriver) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showForm by remember { mutableStateOf(false) }
    val canCreate = state.role == DriversViewModel.ADMIN || state.role == DriversViewModel.WORKER

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack) { Text("Voltar") }
            Text("Motoristas", style = MaterialTheme.typography.titleLarge)
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

        if (canCreate) {
            AdminPrimaryButton(
                onClick = { showForm = true },
                enabled = !state.saving,
                text = "Novo motorista",
            )
        }
        if (showForm || state.editingId != null) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text("Nome") },
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
            if (state.isAdmin) {
                Text("Tipo do motorista", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminChoiceChip(
                        selected = state.driverType == DriversViewModel.CASA,
                        onClick = { onDriverTypeChange(DriversViewModel.CASA) },
                        enabled = !state.saving,
                        label = if (state.driverType == DriversViewModel.CASA) "Casa ✓" else "Casa",
                    )
                    AdminChoiceChip(
                        selected = state.driverType == DriversViewModel.FLETERO,
                        onClick = { onDriverTypeChange(DriversViewModel.FLETERO) },
                        enabled = !state.saving,
                        label = if (state.driverType == DriversViewModel.FLETERO) "Fletero ✓" else "Fletero",
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = onSave,
                    enabled = !state.saving && !state.loading,
                    loading = state.saving,
                    text = if (state.saving) "Salvando…" else if (state.editingId == null) "Criar" else "Salvar",
                )
                AdminSecondaryButton(
                    onClick = { showForm = false; onCancel() },
                    enabled = !state.saving,
                    text = "Cancelar",
                )
            }
        }

        state.drivers.forEach { driver ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(driver.name, style = MaterialTheme.typography.titleMedium)
                    Text(driver.licensePlate.orEmpty())
                    Text(if (driver.driverType == AdminDriverResponseDriver.DriverType.casa) "Casa" else "Fletero")
                    Text(if (driver.isActive == 1) "Ativo" else "Inativo")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (state.canEdit(driver)) {
                            AdminSecondaryButton(onClick = { onEdit(driver) }, text = "Editar")
                        }
                        if (state.canToggle(driver)) {
                            AdminSecondaryButton(
                                onClick = { onToggle(driver) },
                                text = if (driver.isActive == 1) "Desativar" else "Ativar",
                            )
                        }
                    }
                }
            }
        }
        if (!state.loading && state.drivers.isEmpty()) Text("Nenhum motorista")
    }
}

@Composable
fun DriversRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: DriversViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = sessionKey),
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    DriversScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::load,
        onNameChange = viewModel::onNameChange,
        onLicensePlateChange = viewModel::onLicensePlateChange,
        onDriverTypeChange = viewModel::onDriverTypeChange,
        onSave = viewModel::save,
        onCancel = viewModel::cancelEdit,
        onEdit = viewModel::edit,
        onToggle = viewModel::toggle,
        modifier = modifier,
    )
}
