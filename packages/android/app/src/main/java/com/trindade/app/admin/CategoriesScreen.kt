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
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.ui.components.AdminChoiceChip
import com.trindade.app.ui.components.AdminDestructiveButton
import com.trindade.app.ui.components.AdminDestructiveConfirmButton
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton

/** Category catalog UI. Accepts ViewModel state and callbacks directly; role gating fails closed for non-admin. */
@Composable
fun CategoriesScreen(
    state: CategoriesViewModel.UiState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNameChange: (String) -> Unit = {},
    onNamePtChange: (String) -> Unit = {},
    onNameEsChange: (String) -> Unit = {},
    onCategoryTypeChange: (String) -> Unit = {},
    onSortOrderChange: (String) -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    onEdit: (AdminCategoryResponseCategory) -> Unit = {},
    onToggle: (AdminCategoryResponseCategory) -> Unit = {},
    onRequestDelete: (AdminCategoryResponseCategory) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showForm by remember { mutableStateOf(false) }
    var localDeleteTarget by remember { mutableStateOf<AdminCategoryResponseCategory?>(null) }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack) { Text("Voltar") }
            Text("Categorias", style = MaterialTheme.typography.titleLarge)
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
                text = "Nova categoria",
            )
        }

        if (state.isAdmin && (showForm || state.editingId != null)) {
            if (state.editingId == null) {
                if (state.isSpanish) {
                    OutlinedTextField(
                        value = state.nameEs,
                        onValueChange = onNameChange,
                        label = { Text("Español") },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = state.namePt,
                        onValueChange = onNameChange,
                        label = { Text("Português") },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                OutlinedTextField(
                    value = state.namePt,
                    onValueChange = onNamePtChange,
                    label = { Text("Português") },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.nameEs,
                    onValueChange = onNameEsChange,
                    label = { Text("Español") },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text("Tipo", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.CHECK,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.CHECK) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.CHECK) "Check ✓" else "Check",
                )
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.TEMPERATURE,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.TEMPERATURE) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.TEMPERATURE) "Temperatura ✓" else "Temperatura",
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.CHECK_ASSAI,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.CHECK_ASSAI) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.CHECK_ASSAI) "Check Assaí ✓" else "Check Assaí",
                )
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.CHECK_NORMAL,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.CHECK_NORMAL) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.CHECK_NORMAL) "Check Normal ✓" else "Check Normal",
                )
            }

            OutlinedTextField(
                value = state.sortOrder,
                onValueChange = onSortOrderChange,
                label = { Text("Ordem") },
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

        state.categories.forEach { category ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val primaryName = if (state.isSpanish && category.nameEs.isNotBlank()) {
                        category.nameEs
                    } else {
                        category.namePt.ifBlank { category.nameEs }
                    }
                    Text(primaryName, style = MaterialTheme.typography.titleMedium)
                    if (category.namePt.isNotBlank() && category.nameEs.isNotBlank() && category.namePt != category.nameEs) {
                        val secondaryName = if (state.isSpanish) category.namePt else category.nameEs
                        Text(secondaryName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(category.categoryType.label())
                    Text("Ordem: ${category.sortOrder}")
                    Text(if (category.isActive == 1) "Ativa" else "Inativa")

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (state.canEdit(category)) {
                            AdminSecondaryButton(onClick = { onEdit(category) }, text = "Editar")
                        } else {
                            Text("Somente leitura", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canToggle(category)) {
                            AdminSecondaryButton(
                                onClick = { onToggle(category) },
                                text = if (category.isActive == 1) "Desativar" else "Ativar",
                            )
                        }
                        if (state.canDelete(category)) {
                            AdminDestructiveButton(
                                onClick = {
                                    onRequestDelete(category)
                                    localDeleteTarget = category
                                },
                                enabled = !state.saving,
                                text = "Excluir",
                            )
                        }
                    }
                }
            }
        }

        if (!state.loading && state.categories.isEmpty()) {
            Text("Nenhuma categoria")
        }
    }

    val targetToDelete = state.deleteTarget ?: localDeleteTarget
    targetToDelete?.let { category ->
        val categoryName = if (state.isSpanish && category.nameEs.isNotBlank()) {
            category.nameEs
        } else {
            category.namePt.ifBlank { category.nameEs }
        }
        AlertDialog(
            onDismissRequest = {
                localDeleteTarget = null
                onDismissDelete()
            },
            title = { Text("Confirmar exclusão") },
            text = { Text("Excluir $categoryName?") },
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

@Composable
fun CategoriesRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = sessionKey),
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    CategoriesScreen(
        state = state,
        onBack = onBack,
        onRefresh = viewModel::load,
        onNameChange = viewModel::onNameChange,
        onNamePtChange = viewModel::onNamePtChange,
        onNameEsChange = viewModel::onNameEsChange,
        onCategoryTypeChange = viewModel::onCategoryTypeChange,
        onSortOrderChange = viewModel::onSortOrderChange,
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


private fun AdminCategoryResponseCategory.CategoryType.label(): String = when (this) {
    AdminCategoryResponseCategory.CategoryType.temperature -> "Temperatura"
    AdminCategoryResponseCategory.CategoryType.check_assai -> "Check Assaí"
    AdminCategoryResponseCategory.CategoryType.check_normal -> "Check Normal"
    AdminCategoryResponseCategory.CategoryType.check -> "Check"
}
