package com.trindade.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.contract.models.AdminDriverResponseDriver
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.AdminChoiceChip
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.filterPhoneDigits

/** Driver catalog UI. The ViewModel state supplies role and ownership policy. */
@Composable
fun DriversScreen(
    state: DriversViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onDriverTypeChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onEdit: (AdminDriverResponseDriver) -> Unit,
    onToggle: (AdminDriverResponseDriver) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showForm by remember { mutableStateOf(false) }
    var wasSaving by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(state.saving) {
        if (wasSaving && !state.saving && state.error == null) {
            showForm = false
        }
        wasSaving = state.saving
    }
    val canCreate = state.role == DriversViewModel.ADMIN || state.role == DriversViewModel.WORKER

    val casaLabel = stringResource(R.string.drivers_casa)
    val fleteroLabel = stringResource(R.string.drivers_fletero)

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack, text = stringResource(R.string.report_back))
            Text(stringResource(R.string.drivers_title), style = MaterialTheme.typography.titleLarge)
            NavigationActionButton(
                onClick = onRefresh,
                text = stringResource(R.string.drivers_update),
                enabled = !state.loading && !state.saving,
            )
        }
        if (state.loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.admin_loading))
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (canCreate) {
            AdminPrimaryButton(
                onClick = { showForm = true },
                enabled = !state.saving,
                text = stringResource(R.string.drivers_new_driver),
            )
        }
        if (showForm || state.editingId != null) {
            OutlinedTextField(
                value = state.name,
                onValueChange = onNameChange,
                label = { Text(stringResource(R.string.drivers_name)) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            // The number is optional, formatted with Brazilian phone mask.
            OutlinedTextField(
                value = state.phone,
                onValueChange = { onPhoneChange(filterPhoneDigits(it)) },
                visualTransformation = BrazilianPhoneVisualTransformation(),
                label = { Text(stringResource(R.string.drivers_phone)) },
                placeholder = { Text(stringResource(R.string.whatsapp_phone_placeholder)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                singleLine = true,
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.isAdmin) {
                Text(stringResource(R.string.drivers_type_label), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AdminChoiceChip(
                        selected = state.driverType == DriversViewModel.CASA,
                        onClick = { onDriverTypeChange(DriversViewModel.CASA) },
                        enabled = !state.saving,
                        label = if (state.driverType == DriversViewModel.CASA) "$casaLabel ✓" else casaLabel,
                    )
                    AdminChoiceChip(
                        selected = state.driverType == DriversViewModel.FLETERO,
                        onClick = { onDriverTypeChange(DriversViewModel.FLETERO) },
                        enabled = !state.saving,
                        label = if (state.driverType == DriversViewModel.FLETERO) "$fleteroLabel ✓" else fleteroLabel,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = onSave,
                    enabled = !state.saving && !state.loading,
                    loading = state.saving,
                    text = if (state.saving) stringResource(R.string.drivers_saving)
                    else if (state.editingId == null) stringResource(R.string.drivers_create)
                    else stringResource(R.string.drivers_save),
                )
                AdminSecondaryButton(
                    onClick = { showForm = false; onCancel() },
                    enabled = !state.saving,
                    text = stringResource(R.string.drivers_cancel),
                )
            }
        }

        state.drivers.forEach { driver ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(driver.name, style = MaterialTheme.typography.titleMedium)
                    // The number the fleet keeps, blank when nobody typed one: the list exists to show
                    // who can be reached on WhatsApp, and an empty line is that answer.
                    Text(driver.phone.orEmpty())
                    Text(if (driver.driverType == AdminDriverResponseDriver.DriverType.casa) stringResource(R.string.drivers_casa) else stringResource(R.string.drivers_fletero))
                    Text(if (driver.isActive == 1) stringResource(R.string.drivers_chip_active) else stringResource(R.string.drivers_chip_inactive))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (state.canEdit(driver)) {
                            AdminSecondaryButton(onClick = { onEdit(driver) }, text = stringResource(R.string.drivers_edit))
                        }
                        if (state.canToggle(driver)) {
                            AdminSecondaryButton(
                                onClick = { onToggle(driver) },
                                text = if (driver.isActive == 1) stringResource(R.string.drivers_deactivate) else stringResource(R.string.drivers_activate),
                            )
                        }
                    }
                }
            }
        }
        if (!state.loading && state.drivers.isEmpty()) Text(stringResource(R.string.admin_no_drivers))
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
        onPhoneChange = viewModel::onPhoneChange,
        onDriverTypeChange = viewModel::onDriverTypeChange,
        onSave = viewModel::save,
        onCancel = viewModel::cancelEdit,
        onEdit = viewModel::edit,
        onToggle = viewModel::toggle,
        modifier = modifier,
    )
}
