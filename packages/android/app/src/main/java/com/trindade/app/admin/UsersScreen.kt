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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.ui.components.NavigationActionButton

@Composable
fun UsersScreen(
    state: UsersViewModel.UiState,
    onBack: () -> Unit,
    onOpenCreate: () -> Unit,
    onOpenEdit: (AdminUserResponseUser) -> Unit,
    onUsernameChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onRoleIdChanged: (Int) -> Unit,
    onIsActiveChanged: (Int) -> Unit,
    onSave: () -> Unit,
    onCancelForm: () -> Unit,
    onToggleActive: (AdminUserResponseUser) -> Unit,
    onRequestDelete: (AdminUserResponseUser) -> Unit,
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
            state.deleteConfirmUser != null -> onCancelDelete()
            state.form != null -> onCancelForm()
            else -> onBack()
        }
    }

    BackHandler(enabled = state.deleteConfirmUser != null || state.form != null) {
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
            Text(stringResource(R.string.users_title), style = MaterialTheme.typography.titleLarge)
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
            UserForm(
                state = state,
                onUsernameChanged = onUsernameChanged,
                onDisplayNameChanged = onDisplayNameChanged,
                onPasswordChanged = onPasswordChanged,
                onRoleIdChanged = onRoleIdChanged,
                onIsActiveChanged = onIsActiveChanged,
                onSave = onSave,
                onCancel = onCancelForm,
            )
        } else {
            if (state.isAdmin) {
                Button(onClick = onOpenCreate, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.users_add_new))
                }
            }
            if (state.users.isEmpty()) {
                Text(stringResource(R.string.users_empty))
            } else {
                state.users.forEach { user ->
                    UserRow(
                        user = user,
                        state = state,
                        onEdit = onOpenEdit,
                        onToggleActive = onToggleActive,
                        onDelete = onRequestDelete,
                    )
                }
            }
        }
    }

    state.deleteConfirmUser?.let { user ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(stringResource(R.string.users_delete_confirm_title)) },
            text = { Text(stringResource(R.string.users_delete_confirm_body, user.displayName)) },
            confirmButton = {
                TextButton(onClick = onConfirmDelete, enabled = !state.deleting) {
                    if (state.deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.users_delete))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onCancelDelete, enabled = !state.deleting) {
                    Text(stringResource(R.string.users_cancel))
                }
            },
        )
    }
}

@Composable
private fun UserRow(
    user: AdminUserResponseUser,
    state: UsersViewModel.UiState,
    onEdit: (AdminUserResponseUser) -> Unit,
    onToggleActive: (AdminUserResponseUser) -> Unit,
    onDelete: (AdminUserResponseUser) -> Unit,
) {
    val roleText = if (user.roleId == 1) {
        stringResource(R.string.users_role_admin)
    } else {
        stringResource(R.string.users_role_worker)
    }
    val toggleDescription = stringResource(
        if (user.isActive == 1) R.string.users_deactivate else R.string.users_activate,
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(user.displayName, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.users_username_value, user.username))
            Text(stringResource(R.string.users_role_value, roleText))
            Text(
                stringResource(
                    if (user.isActive == 1) R.string.users_status_active else R.string.users_status_inactive,
                ),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canToggleActive(user)) {
                    Switch(
                        checked = user.isActive == 1,
                        onCheckedChange = { onToggleActive(user) },
                        modifier = Modifier.semantics {
                            contentDescription = toggleDescription
                            liveRegion = LiveRegionMode.Polite
                        },
                    )
                }
                if (state.canEdit(user)) {
                    TextButton(onClick = { onEdit(user) }) {
                        Text(stringResource(R.string.users_edit))
                    }
                }
                if (state.canDelete(user)) {
                    TextButton(onClick = { onDelete(user) }) {
                        Text(stringResource(R.string.users_delete))
                    }
                }
            }
        }
    }
}

@Composable
private fun UserForm(
    state: UsersViewModel.UiState,
    onUsernameChanged: (String) -> Unit,
    onDisplayNameChanged: (String) -> Unit,
    onPasswordChanged: (String) -> Unit,
    onRoleIdChanged: (Int) -> Unit,
    onIsActiveChanged: (Int) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val form = state.form ?: return

    Text(
        text = stringResource(if (form.isEditing) R.string.users_edit_title else R.string.users_create_title),
        style = MaterialTheme.typography.titleMedium,
    )

    OutlinedTextField(
        value = form.username,
        onValueChange = onUsernameChanged,
        label = { Text(stringResource(R.string.users_username)) },
        singleLine = true,
        enabled = !form.isSelf,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = form.displayName,
        onValueChange = onDisplayNameChanged,
        label = { Text(stringResource(R.string.users_display_name)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = form.password,
        onValueChange = onPasswordChanged,
        label = { Text(stringResource(R.string.users_password)) },
        placeholder = if (form.isEditing) {
            { Text(stringResource(R.string.users_password_placeholder_edit)) }
        } else null,
        supportingText = { Text(stringResource(R.string.users_password_helper)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    if (!form.isSelf) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.users_role), style = MaterialTheme.typography.bodyMedium)
            FilterChip(
                selected = form.roleId == 2,
                onClick = { onRoleIdChanged(2) },
                label = { Text(stringResource(R.string.users_role_worker)) },
            )
            FilterChip(
                selected = form.roleId == 1,
                onClick = { onRoleIdChanged(1) },
                label = { Text(stringResource(R.string.users_role_admin)) },
            )
        }

        if (form.isEditing) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (form.isActive == 1) R.string.users_status_active else R.string.users_status_inactive,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Switch(
                    checked = form.isActive == 1,
                    onCheckedChange = { checked -> onIsActiveChanged(if (checked) 1 else 0) },
                )
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onSave, enabled = form.canSubmit) {
            if (form.saving) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(if (form.isEditing) R.string.users_update else R.string.users_create))
            }
        }
        TextButton(onClick = onCancel, enabled = !form.saving) {
            Text(stringResource(R.string.users_cancel))
        }
    }
}

@Composable
fun UsersRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UsersViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.loadData() }

    BackHandler(enabled = state.deleteConfirmUser != null || state.form != null) {
        viewModel.handleBack()
    }

    UsersScreen(
        state = state,
        onBack = {
            if (!viewModel.handleBack()) {
                onBack()
            }
        },
        onOpenCreate = viewModel::openCreateForm,
        onOpenEdit = viewModel::openEditForm,
        onUsernameChanged = viewModel::onUsernameChanged,
        onDisplayNameChanged = viewModel::onDisplayNameChanged,
        onPasswordChanged = viewModel::onPasswordChanged,
        onRoleIdChanged = viewModel::onRoleIdChanged,
        onIsActiveChanged = viewModel::onIsActiveChanged,
        onSave = viewModel::saveUser,
        onCancelForm = viewModel::closeForm,
        onToggleActive = viewModel::toggleUserActive,
        onRequestDelete = viewModel::requestDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::confirmDelete,
        modifier = modifier,
    )
}
