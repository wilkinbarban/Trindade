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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.ui.components.NavigationActionButton

@Composable
fun CategoriesScreen(
    state: CategoriesViewModel.UiState,
    onBack: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenEdit: (AdminCategoryResponseCategory) -> Unit,
    onNamePtChanged: (String) -> Unit,
    onNameEsChanged: (String) -> Unit,
    onCategoryTypeChanged: (AdminCategoryResponseCategory.CategoryType) -> Unit,
    onSortOrderChanged: (String) -> Unit,
    onParentCategoryChanged: (Int?) -> Unit,
    onSave: () -> Unit,
    onCancelForm: () -> Unit,
    onToggleActive: (AdminCategoryResponseCategory) -> Unit,
    onRequestDelete: (AdminCategoryResponseCategory) -> Unit,
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
            state.deleteConfirmCategory != null -> onCancelDelete()
            state.form != null -> onCancelForm()
            else -> onBack()
        }
    }

    BackHandler(enabled = state.deleteConfirmCategory != null || state.form != null) {
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
            Text(stringResource(R.string.categories_title), style = MaterialTheme.typography.titleLarge)
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
            CategoryForm(
                state = state,
                onNamePtChanged = onNamePtChanged,
                onNameEsChanged = onNameEsChanged,
                onCategoryTypeChanged = onCategoryTypeChanged,
                onSortOrderChanged = onSortOrderChanged,
                onParentCategoryChanged = onParentCategoryChanged,
                onSave = onSave,
                onCancel = onCancelForm,
            )
        } else {
            if (state.isAdmin) {
                Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.categories_add_new))
                }
            }
            if (state.categories.isEmpty()) {
                Text(stringResource(R.string.categories_empty))
            } else {
                state.categories.forEach { category ->
                    CategoryRow(
                        category = category,
                        state = state,
                        onEdit = onOpenEdit,
                        onToggleActive = onToggleActive,
                        onDelete = onRequestDelete,
                    )
                }
            }
        }
    }

    state.deleteConfirmCategory?.let { category ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.categories_delete_confirm_title)) },
            text = { Text(stringResource(R.string.categories_delete_confirm_body, category.namePt)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.deleting) {
                    if (state.deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.categories_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.deleting) {
                    Text(stringResource(R.string.categories_cancel))
                }
            },
        )
    }
}

@Composable
private fun CategoryRow(
    category: AdminCategoryResponseCategory,
    state: CategoriesViewModel.UiState,
    onEdit: (AdminCategoryResponseCategory) -> Unit,
    onToggleActive: (AdminCategoryResponseCategory) -> Unit,
    onDelete: (AdminCategoryResponseCategory) -> Unit,
) {
    val toggleDescription = stringResource(
        if (category.isActive == 1) R.string.categories_deactivate else R.string.categories_activate,
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(category.namePt, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.categories_name_es_value, category.nameEs))
            Text(stringResource(R.string.categories_type_value, categoryTypeLabel(category.categoryType)))
            Text(stringResource(R.string.categories_order_value, category.sortOrder))
            Text(
                stringResource(
                    if (category.isActive == 1) R.string.categories_status_active else R.string.categories_status_inactive,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canToggleActive(category)) {
                    Switch(
                        checked = category.isActive == 1,
                        onCheckedChange = { onToggleActive(category) },
                        modifier = Modifier.semantics {
                            contentDescription = toggleDescription
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (state.canEdit(category)) {
                    TextButton(onClick = { onEdit(category) }) {
                        Text(stringResource(R.string.categories_edit))
                    }
                }
                if (state.canDelete(category)) {
                    TextButton(onClick = { onDelete(category) }) {
                        Text(stringResource(R.string.categories_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryForm(
    state: CategoriesViewModel.UiState,
    onNamePtChanged: (String) -> Unit,
    onNameEsChanged: (String) -> Unit,
    onCategoryTypeChanged: (AdminCategoryResponseCategory.CategoryType) -> Unit,
    onSortOrderChanged: (String) -> Unit,
    onParentCategoryChanged: (Int?) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form ?: return
    var typeMenuOpen by remember { mutableStateOf(false) }

    Text(
        text = stringResource(if (form.isEditing) R.string.categories_edit_title else R.string.categories_create_title),
        style = MaterialTheme.typography.titleMedium,
    )

    OutlinedTextField(
        value = form.namePt,
        onValueChange = onNamePtChanged,
        label = { Text(stringResource(R.string.categories_name_pt)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = form.nameEs,
        onValueChange = onNameEsChanged,
        label = { Text(stringResource(R.string.categories_name_es)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Box {
        OutlinedButton(
            onClick = { typeMenuOpen = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.categories_type_value, categoryTypeLabel(form.categoryType)))
        }
        DropdownMenu(
            expanded = typeMenuOpen,
            onDismissRequest = { typeMenuOpen = false },
        ) {
            AdminCategoryResponseCategory.CategoryType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(categoryTypeLabel(type)) },
                    onClick = {
                        typeMenuOpen = false
                        onCategoryTypeChanged(type)
                    },
                )
            }
        }
    }

    OutlinedTextField(
        value = form.sortOrder,
        onValueChange = onSortOrderChanged,
        label = { Text(stringResource(R.string.categories_order)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = form.canSubmit) {
            if (form.saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (form.isEditing) R.string.categories_update else R.string.categories_create))
            }
        }
        TextButton(onClick = onCancel, enabled = !form.saving) {
            Text(stringResource(R.string.categories_cancel))
        }
    }
}

@Composable
private fun categoryTypeLabel(type: AdminCategoryResponseCategory.CategoryType): String = when (type) {
    AdminCategoryResponseCategory.CategoryType.check -> stringResource(R.string.categories_type_check)
    AdminCategoryResponseCategory.CategoryType.temperature -> stringResource(R.string.categories_type_temperature)
    AdminCategoryResponseCategory.CategoryType.check_assai -> stringResource(R.string.categories_type_check_assai)
    AdminCategoryResponseCategory.CategoryType.check_normal -> stringResource(R.string.categories_type_check_normal)
}

@Composable
fun CategoriesRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadData() }

    BackHandler(enabled = state.deleteConfirmCategory != null || state.form != null) {
        viewModel.handleBack()
    }

    CategoriesScreen(
        state = state,
        onBack = {
            if (!viewModel.handleBack()) {
                onBack()
            }
        },
        onOpenCreate = viewModel::openCreateForm,
        onOpenEdit = viewModel::openEditForm,
        onNamePtChanged = viewModel::onNamePtChanged,
        onNameEsChanged = viewModel::onNameEsChanged,
        onCategoryTypeChanged = viewModel::onCategoryTypeChanged,
        onSortOrderChanged = viewModel::onSortOrderChanged,
        onParentCategoryChanged = viewModel::onParentCategoryChanged,
        onSave = viewModel::saveCategory,
        onCancelForm = viewModel::closeForm,
        onToggleActive = viewModel::toggleCategoryActive,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        modifier = modifier,
    )
}
