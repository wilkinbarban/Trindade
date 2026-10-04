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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import com.trindade.app.contract.models.AdminTasksResponseTasksInner
import com.trindade.app.ui.components.AdminDestructiveButton
import com.trindade.app.ui.components.AdminDestructiveConfirmButton
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton

/** Task catalog UI. Role checks come from the server-backed ViewModel state, never from navigation. */
@Composable
fun TasksScreen(
    state: TasksViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCategoryChange: (String) -> Unit,
    onNamePtChange: (String) -> Unit,
    onNameEsChange: (String) -> Unit,
    onReadingsChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
    onEdit: (AdminTasksResponseTasksInner) -> Unit,
    onToggle: (AdminTasksResponseTasksInner) -> Unit,
    onRequestDelete: (AdminTasksResponseTasksInner) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showForm by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack) { Text("Voltar") }
            Text("Tarefas", style = MaterialTheme.typography.titleLarge)
            NavigationActionButton(
                onClick = onRefresh,
                enabled = !state.loading && !state.saving,
            ) {
                Text("Atualizar")
            }
        }
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.role == TasksViewModel.ADMIN || state.role == TasksViewModel.WORKER) {
            AdminPrimaryButton(
                onClick = { showForm = true },
                enabled = !state.saving,
                text = "Nova tarefa",
            )
        }
        if (showForm || state.editingId != null) {
            TaskForm(state, onCategoryChange, onNamePtChange, onNameEsChange, onReadingsChange)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = onSave,
                    enabled = !state.saving,
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

        state.tasks.forEach { task ->
            val editable = state.canEdit(task)
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.categoryName, style = MaterialTheme.typography.labelMedium)
                    Text(task.namePt.ifBlank { task.nameEs }, style = MaterialTheme.typography.titleMedium)
                    Text(task.taskType.label())
                    if (task.taskType == AdminTasksResponseTasksInner.TaskType.temperature) {
                        Text("${task.temperatureReadings} leituras")
                    }
                    Text(if (task.isActive == 1) "Ativa" else "Inativa")
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (editable) {
                            AdminSecondaryButton(onClick = { onEdit(task) }, text = "Editar")
                        } else {
                            Text("Somente leitura", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canToggle(task)) {
                            AdminSecondaryButton(
                                onClick = { onToggle(task) },
                                text = if (task.isActive == 1) "Desativar" else "Ativar",
                            )
                        }
                        if (state.canDelete(task)) {
                            AdminDestructiveButton(
                                onClick = { onRequestDelete(task) },
                                enabled = !state.saving,
                                text = "Excluir",
                            )
                        }
                    }
                }
            }
        }
        if (!state.loading && state.tasks.isEmpty()) Text("Nenhuma tarefa")
    }

    state.deleteTarget?.let { task ->
        val taskName = task.namePt.ifBlank { task.nameEs }
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text("Confirmar exclusão") },
            text = { Text("Excluir $taskName?") },
            confirmButton = {
                AdminDestructiveConfirmButton(
                    onClick = onConfirmDelete,
                    text = "Confirmar",
                )
            },
            dismissButton = {
                AdminSecondaryButton(
                    onClick = onDismissDelete,
                    text = "Cancelar",
                )
            },
        )
    }
}

@Composable
private fun TaskForm(
    state: TasksViewModel.UiState,
    onCategoryChange: (String) -> Unit,
    onNamePtChange: (String) -> Unit,
    onNameEsChange: (String) -> Unit,
    onReadingsChange: (String) -> Unit,
) {
    var categoriesOpen by remember { mutableStateOf(false) }
    var readingsOpen by remember { mutableStateOf(false) }
    val selected = state.categories.firstOrNull { it.id.toString() == state.categoryId }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Categoria", style = MaterialTheme.typography.labelLarge)
        AdminSecondaryButton(
            onClick = { categoriesOpen = true },
            text = selected?.namePt ?: "Selecione uma categoria",
        )
        DropdownMenu(expanded = categoriesOpen, onDismissRequest = { categoriesOpen = false }) {
            state.categories.forEach { category ->
                DropdownMenuItem(
                    text = { Text(category.namePt) },
                    onClick = { categoriesOpen = false; onCategoryChange(category.id.toString()) },
                )
            }
        }
        OutlinedTextField(state.namePt, onNamePtChange, label = { Text("Português") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(state.nameEs, onNameEsChange, label = { Text("Español") }, modifier = Modifier.fillMaxWidth())
        if (selected?.categoryType == com.trindade.app.contract.models.AdminCategoryResponseCategory.CategoryType.temperature) {
            Text("Leituras de temperatura", style = MaterialTheme.typography.labelLarge)
            AdminSecondaryButton(
                onClick = { readingsOpen = true },
                text = state.temperatureReadings,
            )
            DropdownMenu(expanded = readingsOpen, onDismissRequest = { readingsOpen = false }) {
                (1..3).forEach { value ->
                    DropdownMenuItem(text = { Text(value.toString()) }, onClick = {
                        readingsOpen = false; onReadingsChange(value.toString())
                    })
                }
            }
        }
    }
}

private fun AdminTasksResponseTasksInner.TaskType.label(): String = when (this) {
    AdminTasksResponseTasksInner.TaskType.temperature -> "Temperatura"
    AdminTasksResponseTasksInner.TaskType.check_assai -> "Check Assaí"
    AdminTasksResponseTasksInner.TaskType.check_normal -> "Check Normal"
    AdminTasksResponseTasksInner.TaskType.check -> "Check"
}

@Composable
fun TasksRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TasksViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = sessionKey),
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    TasksScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::load,
        onCategoryChange = viewModel::onCategoryChange,
        onNamePtChange = viewModel::onNamePtChange,
        onNameEsChange = viewModel::onNameEsChange,
        onReadingsChange = viewModel::onTemperatureReadingsChange,
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
