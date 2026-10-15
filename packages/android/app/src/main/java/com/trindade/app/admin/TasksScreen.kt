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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.ui.components.NavigationActionButton

@Composable
fun TasksScreen(
    state: TasksViewModel.UiState,
    onBack: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenEdit: (AdminTasksResponseTasksInner) -> Unit,
    onCategoryChanged: (Int) -> Unit,
    onNamePtChanged: (String) -> Unit,
    onNameEsChanged: (String) -> Unit,
    onTemperatureReadingsChanged: (Int) -> Unit,
    onSave: () -> Unit,
    onCancelForm: () -> Unit,
    onToggleActive: (AdminTasksResponseTasksInner) -> Unit,
    onRequestDelete: (AdminTasksResponseTasksInner) -> Unit,
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
            state.deleteConfirmTask != null -> onCancelDelete()
            state.form != null -> onCancelForm()
            else -> onBack()
        }
    }

    BackHandler(enabled = state.deleteConfirmTask != null || state.form != null) {
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
            Text(stringResource(R.string.tasks_title), style = MaterialTheme.typography.titleLarge)
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
            TaskForm(
                state = state,
                onCategoryChanged = onCategoryChanged,
                onNamePtChanged = onNamePtChanged,
                onNameEsChanged = onNameEsChanged,
                onTemperatureReadingsChanged = onTemperatureReadingsChanged,
                onSave = onSave,
                onCancel = onCancelForm,
            )
        } else {
            Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.tasks_add_new))
            }
            if (state.tasks.isEmpty()) {
                Text(stringResource(R.string.tasks_empty))
            } else {
                state.tasks.forEach { task ->
                    TaskRow(
                        task = task,
                        state = state,
                        onEdit = onOpenEdit,
                        onToggleActive = onToggleActive,
                        onDelete = onRequestDelete,
                    )
                }
            }
        }
    }

    state.deleteConfirmTask?.let { task ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.tasks_delete_confirm_title)) },
            text = { Text(stringResource(R.string.tasks_delete_confirm_body, task.namePt)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.deleting) {
                    if (state.deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.tasks_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.deleting) {
                    Text(stringResource(R.string.tasks_cancel))
                }
            },
        )
    }
}

@Composable
private fun TaskRow(
    task: AdminTasksResponseTasksInner,
    state: TasksViewModel.UiState,
    onEdit: (AdminTasksResponseTasksInner) -> Unit,
    onToggleActive: (AdminTasksResponseTasksInner) -> Unit,
    onDelete: (AdminTasksResponseTasksInner) -> Unit,
) {
    val toggleDescription = stringResource(
        if (task.isActive == 1) R.string.tasks_deactivate else R.string.tasks_activate,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(task.namePt, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.tasks_name_es_value, task.nameEs))
            Text(stringResource(R.string.tasks_category_value, task.categoryName))
            Text(stringResource(R.string.tasks_type_value, taskTypeLabel(task.taskType)))
            Text(
                stringResource(
                    if (task.isActive == 1) R.string.tasks_status_active else R.string.tasks_status_inactive,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canToggleActive(task)) {
                    Switch(
                        checked = task.isActive == 1,
                        onCheckedChange = { onToggleActive(task) },
                        modifier = Modifier.semantics {
                            contentDescription = toggleDescription
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (state.canEdit(task)) {
                    TextButton(onClick = { onEdit(task) }) {
                        Text(stringResource(R.string.tasks_edit))
                    }
                }
                if (state.canDelete(task)) {
                    TextButton(onClick = { onDelete(task) }) {
                        Text(stringResource(R.string.tasks_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskForm(
    state: TasksViewModel.UiState,
    onCategoryChanged: (Int) -> Unit,
    onNamePtChanged: (String) -> Unit,
    onNameEsChanged: (String) -> Unit,
    onTemperatureReadingsChanged: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form ?: return
    val selectedCategory = state.categories.firstOrNull { it.id == form.categoryId }
    val decreaseReadingsDescription = stringResource(R.string.tasks_decrease_readings)
    val increaseReadingsDescription = stringResource(R.string.tasks_increase_readings)
    var categoryMenuOpen by remember { mutableStateOf(false) }

    Text(
        text = stringResource(if (form.isEditing) R.string.tasks_edit_title else R.string.tasks_create_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Box {
        OutlinedButton(
            onClick = { categoryMenuOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(selectedCategory?.namePt ?: stringResource(R.string.tasks_category_select))
        }
        DropdownMenu(
            expanded = categoryMenuOpen,
            onDismissRequest = { categoryMenuOpen = false },
        ) {
            state.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.namePt) },
                    onClick = {
                        categoryMenuOpen = false
                        onCategoryChanged(category.id)
                    },
                )
            }
        }
    }
    OutlinedTextField(
        value = form.namePt,
        onValueChange = onNamePtChanged,
        label = { Text(stringResource(R.string.tasks_name_pt)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.nameEs,
        onValueChange = onNameEsChanged,
        label = { Text(stringResource(R.string.tasks_name_es)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (selectedCategory?.categoryType == AdminCategoryResponseCategory.CategoryType.temperature) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.tasks_temperature_readings),
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = { onTemperatureReadingsChanged(form.temperatureReadings - 1) },
                enabled = form.temperatureReadings > 1,
                modifier = Modifier.semantics {
                    contentDescription = decreaseReadingsDescription
                },
            ) { Text("−") }
            Text(form.temperatureReadings.toString())
            TextButton(
                onClick = { onTemperatureReadingsChanged(form.temperatureReadings + 1) },
                enabled = form.temperatureReadings < 3,
                modifier = Modifier.semantics {
                    contentDescription = increaseReadingsDescription
                },
            ) { Text("+") }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = form.canSubmit) {
            if (form.saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (form.isEditing) R.string.tasks_update else R.string.tasks_create))
            }
        }
        TextButton(onClick = onCancel, enabled = !form.saving) {
            Text(stringResource(R.string.tasks_cancel))
        }
    }
}

@Composable
private fun taskTypeLabel(type: AdminTasksResponseTasksInner.TaskType): String = when (type) {
    AdminTasksResponseTasksInner.TaskType.check -> stringResource(R.string.tasks_type_check)
    AdminTasksResponseTasksInner.TaskType.temperature -> stringResource(R.string.tasks_type_temperature)
    AdminTasksResponseTasksInner.TaskType.check_assai -> stringResource(R.string.tasks_type_check_assai)
    AdminTasksResponseTasksInner.TaskType.check_normal -> stringResource(R.string.tasks_type_check_normal)
}

@Composable
fun TasksRoute(
    sessionKey: String,
    onBack: () -> Unit,
    viewModel: TasksViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadData() }

    BackHandler(enabled = state.deleteConfirmTask != null || state.form != null) {
        viewModel.handleBack()
    }

    TasksScreen(
        state = state,
        onBack = {
            if (!viewModel.handleBack()) {
                onBack()
            }
        },
        onOpenCreate = viewModel::openCreateForm,
        onOpenEdit = viewModel::openEditForm,
        onCategoryChanged = viewModel::onCategoryChanged,
        onNamePtChanged = viewModel::onNamePtChanged,
        onNameEsChanged = viewModel::onNameEsChanged,
        onTemperatureReadingsChanged = viewModel::onTemperatureReadingsChanged,
        onSave = viewModel::saveTask,
        onCancelForm = viewModel::closeForm,
        onToggleActive = viewModel::toggleTaskActive,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
    )
}
