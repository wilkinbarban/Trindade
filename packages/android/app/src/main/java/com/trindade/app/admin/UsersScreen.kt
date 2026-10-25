package com.trindade.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.contract.models.AdminUserResponseUser
import com.trindade.app.ui.components.AdminConfirmDialog
import com.trindade.app.ui.components.AdminConfirmation
import com.trindade.app.ui.components.AdminDestructiveButton
import com.trindade.app.ui.components.AdminDestructiveConfirmButton
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.AdminResultDialog
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.resolve

/**
 * Renders the user management administration UI driven strictly by [UsersViewModel.UiState].
 *
 * All state mutations are surfaced via direct callbacks following the `on*` convention without
 * aliases or direct deletion bypass.
 */
@Composable
fun UsersScreen(
    state: UsersViewModel.UiState,
    password: String = "",
    onBack: () -> Unit,
    onUsernameChange: (String) -> Unit = {},
    onDisplayNameChange: (String) -> Unit = {},
    onPasswordChange: (String) -> Unit = {},
    onRoleIdChange: (Int) -> Unit = {},
    onIsActiveChange: (Int) -> Unit = {},
    onSave: () -> Unit = {},
    onCancel: () -> Unit = {},
    onEdit: (AdminUserResponseUser) -> Unit = {},
    onToggle: (AdminUserResponseUser) -> Unit = {},
    onRequestDelete: (AdminUserResponseUser) -> Unit = {},
    onConfirmDelete: () -> Unit = {},
    onDismissDelete: () -> Unit = {},
    onDismissOutcome: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showForm by remember { mutableStateOf(false) }
    var localDeleteTarget by remember { mutableStateOf<AdminUserResponseUser?>(null) }
    var pendingConfirmation by remember { mutableStateOf<AdminConfirmation?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack) { Text(stringResource(R.string.admin_back)) }
            Text(stringResource(R.string.admin_section_users), style = MaterialTheme.typography.titleLarge)
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
                text = stringResource(R.string.admin_new_user),
            )
        }

        if (state.isAdmin && (showForm || state.editingId != null)) {
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text(stringResource(R.string.admin_field_username)) },
                enabled = !state.saving && !state.isSelfEditing,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text(stringResource(R.string.admin_field_display_name)) },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text(stringResource(R.string.admin_field_password)) },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.hasPasswordWarning) {
                Text(
                    text = UsersViewModel.PASSWORD_WARNING.resolve(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!state.isSelfEditing) {
                Text(stringResource(R.string.admin_field_role), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.selectable(
                            selected = state.roleId == 1,
                            enabled = !state.saving,
                            role = Role.RadioButton,
                            onClick = { onRoleIdChange(1) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.roleId == 1,
                            onClick = null,
                            enabled = !state.saving,
                        )
                        Text(stringResource(R.string.admin_role_admin))
                    }
                    Row(
                        modifier = Modifier.selectable(
                            selected = state.roleId == 2,
                            enabled = !state.saving,
                            role = Role.RadioButton,
                            onClick = { onRoleIdChange(2) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.roleId == 2,
                            onClick = null,
                            enabled = !state.saving,
                        )
                        Text(stringResource(R.string.admin_role_worker))
                    }
                }
            }

            if (state.editingId != null && !state.isSelfEditing) {
                Text(stringResource(R.string.admin_field_status), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.selectable(
                            selected = state.isActive == 1,
                            enabled = !state.saving,
                            role = Role.RadioButton,
                            onClick = { onIsActiveChange(1) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.isActive == 1,
                            onClick = null,
                            enabled = !state.saving,
                        )
                        Text(stringResource(R.string.admin_status_active))
                    }
                    Row(
                        modifier = Modifier.selectable(
                            selected = state.isActive == 0,
                            enabled = !state.saving,
                            role = Role.RadioButton,
                            onClick = { onIsActiveChange(0) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = state.isActive == 0,
                            onClick = null,
                            enabled = !state.saving,
                        )
                        Text(stringResource(R.string.admin_status_inactive))
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AdminPrimaryButton(
                    onClick = {
                        val isCreate = state.editingId == null
                        val titleRes = if (isCreate) R.string.admin_action_create else R.string.admin_action_save
                        val bodyRes = if (isCreate) R.string.admin_dialog_create_body else R.string.admin_dialog_save_body
                        val userName = state.username.ifBlank { state.displayName }
                        pendingConfirmation = AdminConfirmation(
                            title = context.getString(titleRes),
                            message = context.getString(bodyRes, userName),
                            confirmLabel = context.getString(R.string.admin_action_confirm),
                            onConfirm = {
                                pendingConfirmation = null
                                onSave()
                            },
                        )
                    },
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

        state.users.forEach { user ->
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(user.username, style = MaterialTheme.typography.titleMedium)
                    if (user.displayName.isNotBlank()) {
                        Text(user.displayName)
                    }
                    val roleText = when (user.roleId) {
                        1 -> stringResource(R.string.admin_role_admin)
                        2 -> stringResource(R.string.admin_role_worker)
                        else -> user.roleName.ifBlank { stringResource(R.string.admin_role_unknown, user.roleId) }
                    }
                    Text(roleText)
                    Text(if (user.isActive == 1) stringResource(R.string.admin_status_active) else stringResource(R.string.admin_status_inactive))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (state.canEdit(user)) {
                            AdminSecondaryButton(onClick = { onEdit(user) }, text = stringResource(R.string.admin_action_edit))
                        } else {
                            Text(stringResource(R.string.admin_read_only), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canToggle(user)) {
                            AdminSecondaryButton(
                                onClick = {
                                    val isActivating = user.isActive != 1
                                    val titleRes = if (isActivating) R.string.admin_action_activate else R.string.admin_action_deactivate
                                    val bodyRes = if (isActivating) R.string.admin_dialog_toggle_on_body else R.string.admin_dialog_toggle_off_body
                                    val name = user.username.ifBlank { user.displayName }
                                    pendingConfirmation = AdminConfirmation(
                                        title = context.getString(titleRes),
                                        message = context.getString(bodyRes, name),
                                        confirmLabel = context.getString(R.string.admin_action_confirm),
                                        onConfirm = {
                                            pendingConfirmation = null
                                            onToggle(user)
                                        },
                                    )
                                },
                                text = if (user.isActive == 1) stringResource(R.string.admin_action_deactivate) else stringResource(R.string.admin_action_activate),
                            )
                        }
                        if (state.canDelete(user)) {
                            AdminDestructiveButton(
                                onClick = {
                                    onRequestDelete(user)
                                    localDeleteTarget = user
                                },
                                enabled = !state.saving,
                                text = stringResource(R.string.admin_action_delete),
                            )
                        }
                    }
                }
            }
        }

        if (!state.loading && state.users.isEmpty()) {
            Text(stringResource(R.string.admin_no_users))
        }
    }

    val targetToDelete = (state.deleteTarget ?: localDeleteTarget)?.takeIf { state.canDelete(it) }
    val deleteConfirmation = targetToDelete?.let { user ->
        val name = user.username.ifBlank { user.displayName }
        AdminConfirmation(
            title = stringResource(R.string.admin_action_delete),
            message = stringResource(R.string.admin_dialog_delete_body, name),
            confirmLabel = stringResource(R.string.admin_action_delete),
            isDestructive = true,
            onConfirm = {
                localDeleteTarget = null
                onConfirmDelete()
            },
        )
    }

    AdminConfirmDialog(
        confirmation = deleteConfirmation ?: pendingConfirmation,
        onDismiss = {
            if (deleteConfirmation != null) {
                localDeleteTarget = null
                onDismissDelete()
            } else {
                pendingConfirmation = null
            }
        },
    )

    AdminResultDialog(
        outcome = state.outcome,
        onClose = onDismissOutcome,
    )
}

@Composable
fun UsersRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: UsersViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = sessionKey),
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    UsersScreen(
        state = state,
        onBack = onBack,
        onUsernameChange = viewModel::onUsernameChange,
        onDisplayNameChange = viewModel::onDisplayNameChange,
        onPasswordChange = viewModel::onPasswordChange,
        onRoleIdChange = viewModel::onRoleIdChange,
        onIsActiveChange = viewModel::onIsActiveChange,
        onSave = viewModel::save,
        onCancel = viewModel::cancelEdit,
        onEdit = viewModel::edit,
        onToggle = viewModel::toggle,
        onRequestDelete = viewModel::requestDelete,
        onConfirmDelete = viewModel::confirmDelete,
        onDismissDelete = viewModel::cancelDelete,
        onDismissOutcome = viewModel::dismissOutcome,
        modifier = modifier,
    )
}
