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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.contract.models.AdminCategoryResponseCategory
import com.trindade.app.ui.components.AdminChoiceChip
import com.trindade.app.ui.components.AdminDestructiveButton
import com.trindade.app.ui.components.AdminDestructiveConfirmButton
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.resolve

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
            NavigationActionButton(onClick = onBack) { Text(stringResource(R.string.admin_back)) }
            Text(stringResource(R.string.admin_section_categories), style = MaterialTheme.typography.titleLarge)
            NavigationActionButton(
                onClick = onRefresh,
                enabled = !state.loading && !state.saving,
            ) {
                Text(stringResource(R.string.admin_refresh))
            }
        }

        if (state.loading) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.admin_loading))
            }
        }

        state.error?.let { Text(it.resolve(), color = MaterialTheme.colorScheme.error) }

        if (state.isAdmin) {
            AdminPrimaryButton(
                onClick = { showForm = true },
                enabled = !state.saving,
                text = stringResource(R.string.admin_new_category),
            )
        }

        if (state.isAdmin && (showForm || state.editingId != null)) {
            if (state.editingId == null) {
                if (state.isSpanish) {
                    OutlinedTextField(
                        value = state.nameEs,
                        onValueChange = onNameChange,
                        label = { Text(stringResource(R.string.admin_language_spanish)) },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = state.namePt,
                        onValueChange = onNameChange,
                        label = { Text(stringResource(R.string.admin_language_portuguese)) },
                        enabled = !state.saving,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                OutlinedTextField(
                    value = state.namePt,
                    onValueChange = onNamePtChange,
                    label = { Text(stringResource(R.string.admin_language_portuguese)) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.nameEs,
                    onValueChange = onNameEsChange,
                    label = { Text(stringResource(R.string.admin_language_spanish)) },
                    enabled = !state.saving,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(stringResource(R.string.admin_field_type), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.CHECK,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.CHECK) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.CHECK) "${stringResource(R.string.admin_type_check)} ✓" else stringResource(R.string.admin_type_check),
                )
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.TEMPERATURE,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.TEMPERATURE) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.TEMPERATURE) "${stringResource(R.string.admin_type_temperature)} ✓" else stringResource(R.string.admin_type_temperature),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.CHECK_ASSAI,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.CHECK_ASSAI) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.CHECK_ASSAI) "${stringResource(R.string.admin_type_check_assai)} ✓" else stringResource(R.string.admin_type_check_assai),
                )
                AdminChoiceChip(
                    selected = state.categoryType == CategoriesViewModel.CHECK_NORMAL,
                    onClick = { onCategoryTypeChange(CategoriesViewModel.CHECK_NORMAL) },
                    enabled = !state.saving,
                    label = if (state.categoryType == CategoriesViewModel.CHECK_NORMAL) "${stringResource(R.string.admin_type_check_normal)} ✓" else stringResource(R.string.admin_type_check_normal),
                )
            }

            OutlinedTextField(
                value = state.sortOrder,
                onValueChange = onSortOrderChange,
                label = { Text(stringResource(R.string.admin_field_order)) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = onSave,
                    enabled = !state.saving && !state.loading,
                    loading = state.saving,
                    text = if (state.saving) stringResource(R.string.admin_action_saving) else if (state.editingId == null) stringResource(R.string.admin_action_create) else stringResource(R.string.admin_action_save),
                )
                AdminSecondaryButton(
                    onClick = {
                        showForm = false
                        onCancel()
                    },
                    enabled = !state.saving,
                    text = stringResource(R.string.admin_action_cancel),
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
                    Text(categoryTypeLabel(category.categoryType))
                    Text(stringResource(R.string.admin_category_order, category.sortOrder))
                    Text(if (category.isActive == 1) stringResource(R.string.admin_status_active_fem) else stringResource(R.string.admin_status_inactive_fem))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        if (state.canEdit(category)) {
                            AdminSecondaryButton(onClick = { onEdit(category) }, text = stringResource(R.string.admin_action_edit))
                        } else {
                            Text(stringResource(R.string.admin_read_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canToggle(category)) {
                            AdminSecondaryButton(
                                onClick = { onToggle(category) },
                                text = if (category.isActive == 1) stringResource(R.string.admin_action_deactivate) else stringResource(R.string.admin_action_activate),
                            )
                        }
                        if (state.canDelete(category)) {
                            AdminDestructiveButton(
                                onClick = {
                                    onRequestDelete(category)
                                    localDeleteTarget = category
                                },
                                enabled = !state.saving,
                                text = stringResource(R.string.admin_action_delete),
                            )
                        }
                    }
                }
            }
        }

        if (!state.loading && state.categories.isEmpty()) {
            Text(stringResource(R.string.admin_no_categories))
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
            title = { Text(stringResource(R.string.admin_confirm_delete)) },
            text = { Text(stringResource(R.string.admin_delete_question, categoryName)) },
            confirmButton = {
                AdminDestructiveConfirmButton(
                    onClick = {
                        localDeleteTarget = null
                        onConfirmDelete()
                    },
                    text = stringResource(R.string.admin_action_confirm),
                )
            },
            dismissButton = {
                AdminSecondaryButton(
                    onClick = {
                        localDeleteTarget = null
                        onDismissDelete()
                    },
                    text = stringResource(R.string.admin_action_cancel),
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


@Composable
private fun categoryTypeLabel(type: AdminCategoryResponseCategory.CategoryType): String = when (type) {
    AdminCategoryResponseCategory.CategoryType.temperature -> stringResource(R.string.admin_type_temperature)
    AdminCategoryResponseCategory.CategoryType.check_assai -> stringResource(R.string.admin_type_check_assai)
    AdminCategoryResponseCategory.CategoryType.check_normal -> stringResource(R.string.admin_type_check_normal)
    AdminCategoryResponseCategory.CategoryType.check -> stringResource(R.string.admin_type_check)
}
