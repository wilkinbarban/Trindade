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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trindade.app.contract.models.AdminTasksResponseTasksInner

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
    onDelete: (AdminTasksResponseTasksInner) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showForm by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<AdminTasksResponseTasksInner?>(null) }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Voltar") }
            Text("Tarefas", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading) { Text("Atualizar") }
        }
        if (state.loading) CircularProgressIndicator()
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (state.role == TasksViewModel.ADMIN || state.role == TasksViewModel.WORKER) {
            Button(onClick = { showForm = true }, enabled = !state.saving) { Text("Nova tarefa") }
        }
        if (showForm || state.editingId != null) {
            TaskForm(state, onCategoryChange, onNamePtChange, onNameEsChange, onReadingsChange)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onSave, enabled = !state.saving) {
                    if (state.saving) {
                        CircularProgressIndicator()
                        Text("Salvando…")
                    } else Text(if (state.editingId == null) "Criar" else "Salvar")
                }
                TextButton(onClick = { showForm = false; onCancel() }, enabled = !state.saving) { Text("Cancelar") }
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
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (editable) TextButton(onClick = { onEdit(task) }) { Text("Editar") }
                        else Text("Somente leitura", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (state.canToggle(task)) TextButton(onClick = { onToggle(task) }) {
                            Text(if (task.isActive == 1) "Desativar" else "Ativar")
                        }
                        if (state.canDelete(task)) TextButton(onClick = { deleteTarget = task }) { Text("Excluir") }
                    }
                }
            }
        }
        if (!state.loading && state.tasks.isEmpty()) Text("Nenhuma tarefa")
    }

    deleteTarget?.let { task ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Confirmar exclusão") },
            text = { Text("Excluir ${task.namePt.ifBlank { task.nameEs }}?") },
            confirmButton = { TextButton(onClick = { deleteTarget = null; onDelete(task) }) { Text("Confirmar") } },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Cancelar") } },
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
        Text("Categoria")
        TextButton(onClick = { categoriesOpen = true }) {
            Text(selected?.namePt ?: "Selecione uma categoria")
        }
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
            Text("Leituras de temperatura")
            TextButton(onClick = { readingsOpen = true }) { Text(state.temperatureReadings) }
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
