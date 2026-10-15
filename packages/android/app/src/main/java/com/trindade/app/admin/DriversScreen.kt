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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.ui.components.NavigationActionButton

@Composable
fun DriversScreen(
    state: DriversViewModel.UiState,
    onBack: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenEdit: (AdminDriverResponseDriver) -> Unit,
    onNameChanged: (String) -> Unit,
    onDriverTypeChanged: (DriversViewModel.DriverTypeChoice) -> Unit,
    onSave: () -> Unit,
    onCancelForm: () -> Unit,
    onToggleActive: (AdminDriverResponseDriver) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.loading) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val handleBack = {
        if (state.form != null) onCancelForm() else onBack()
    }

    BackHandler(enabled = state.form != null) {
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
            Text(stringResource(R.string.drivers_title), style = MaterialTheme.typography.titleLarge)
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
            DriverForm(
                state = state,
                onNameChanged = onNameChanged,
                onDriverTypeChanged = onDriverTypeChanged,
                onSave = onSave,
                onCancel = onCancelForm,
            )
        } else {
            Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.drivers_add_new))
            }
            if (state.drivers.isEmpty()) {
                Text(stringResource(R.string.drivers_empty))
            } else {
                state.drivers.forEach { driver ->
                    DriverRow(
                        driver = driver,
                        state = state,
                        onEdit = onOpenEdit,
                        onToggleActive = onToggleActive,
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverRow(
    driver: AdminDriverResponseDriver,
    state: DriversViewModel.UiState,
    onEdit: (AdminDriverResponseDriver) -> Unit,
    onToggleActive: (AdminDriverResponseDriver) -> Unit,
) {
    val toggleDescription = stringResource(
        if (driver.isActive == 1) R.string.drivers_deactivate else R.string.drivers_activate,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(driver.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.drivers_type_value, driverTypeLabel(driver.driverType)))
            Text(
                stringResource(
                    if (driver.isActive == 1) R.string.drivers_status_active else R.string.drivers_status_inactive,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canToggleActive(driver)) {
                    Switch(
                        checked = driver.isActive == 1,
                        onCheckedChange = { onToggleActive(driver) },
                        modifier = Modifier.semantics {
                            contentDescription = toggleDescription
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (state.canEdit(driver)) {
                    TextButton(onClick = { onEdit(driver) }) {
                        Text(stringResource(R.string.drivers_edit))
                    }
                } else if (state.isWorker) {
                    Text(
                        text = stringResource(R.string.drivers_read_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverForm(
    state: DriversViewModel.UiState,
    onNameChanged: (String) -> Unit,
    onDriverTypeChanged: (DriversViewModel.DriverTypeChoice) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form ?: return
    var typeMenuOpen by remember { mutableStateOf(false) }

    Text(
        text = stringResource(if (form.isEditing) R.string.drivers_edit_title else R.string.drivers_create_title),
        style = MaterialTheme.typography.titleMedium,
    )

    OutlinedTextField(
        value = form.name,
        onValueChange = onNameChanged,
        label = { Text(stringResource(R.string.drivers_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (state.isAdmin) {
        Box {
            OutlinedButton(
                onClick = { typeMenuOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when (form.driverType) {
                        DriversViewModel.DriverTypeChoice.CASA -> stringResource(R.string.drivers_type_casa)
                        DriversViewModel.DriverTypeChoice.FLETERO -> stringResource(R.string.drivers_type_fletero)
                    },
                )
            }
            DropdownMenu(
                expanded = typeMenuOpen,
                onDismissRequest = { typeMenuOpen = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drivers_type_fletero)) },
                    onClick = {
                        typeMenuOpen = false
                        onDriverTypeChanged(DriversViewModel.DriverTypeChoice.FLETERO)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.drivers_type_casa)) },
                    onClick = {
                        typeMenuOpen = false
                        onDriverTypeChanged(DriversViewModel.DriverTypeChoice.CASA)
                    },
                )
            }
        }
    } else {
        Text(
            text = stringResource(R.string.drivers_type_value, stringResource(R.string.drivers_type_fletero)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = form.canSubmit) {
            if (form.saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (form.isEditing) R.string.drivers_update else R.string.drivers_create))
            }
        }
        TextButton(onClick = onCancel, enabled = !form.saving) {
            Text(stringResource(R.string.drivers_cancel))
        }
    }
}

@Composable
private fun driverTypeLabel(type: AdminDriverResponseDriver.DriverType): String = when (type) {
    AdminDriverResponseDriver.DriverType.casa -> stringResource(R.string.drivers_type_casa)
    AdminDriverResponseDriver.DriverType.fletero -> stringResource(R.string.drivers_type_fletero)
}

@Composable
fun DriversRoute(
    sessionKey: String,
    onBack: () -> Unit,
    viewModel: DriversViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadData() }

    BackHandler(enabled = state.form != null) {
        viewModel.handleBack()
    }

    DriversScreen(
        state = state,
        onBack = {
            if (!viewModel.handleBack()) {
                onBack()
            }
        },
        onOpenCreate = viewModel::openCreateForm,
        onOpenEdit = viewModel::openEditForm,
        onNameChanged = viewModel::onNameChanged,
        onDriverTypeChanged = viewModel::onDriverTypeChanged,
        onSave = viewModel::saveDriver,
        onCancelForm = viewModel::closeForm,
        onToggleActive = viewModel::toggleDriverActive,
    )
}
