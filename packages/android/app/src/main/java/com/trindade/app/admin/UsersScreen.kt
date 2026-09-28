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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.trindade.app.contract.models.AdminUserResponseUser

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
    onRefresh: () -> Unit,
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
    modifier: Modifier = Modifier,
) {
    var showForm by remember { mutableStateOf(false) }
    var localDeleteTarget by remember { mutableStateOf<AdminUserResponseUser?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onBack) { Text("Voltar") }
            Text("Usuários", style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onRefresh, enabled = !state.loading && !state.saving) { Text("Atualizar") }
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
            Button(onClick = { showForm = true }, enabled = !state.saving) { Text("Novo usuário") }
        }

        if (state.isAdmin && (showForm || state.editingId != null)) {
            OutlinedTextField(
                value = state.username,
                onValueChange = onUsernameChange,
                label = { Text("Nome de usuário") },
                enabled = !state.saving && !state.isSelfEditing,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.displayName,
                onValueChange = onDisplayNameChange,
                label = { Text("Nome de exibição") },
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = password,
                onValueChange = onPasswordChange,
                label = { Text("Senha") },
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth(),
            )

            if (state.hasPasswordWarning) {
                Text(
                    text = UsersViewModel.PASSWORD_WARNING,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            if (!state.isSelfEditing) {
                Text("Função", style = MaterialTheme.typography.labelLarge)
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
                        Text("Administrador")
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
                        Text("Trabalhador")
                    }
                }
            }

            if (state.editingId != null && !state.isSelfEditing) {
                Text("Status", style = MaterialTheme.typography.labelLarge)
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
                        Text("Ativo")
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
                        Text("Inativo")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onSave,
                    enabled = !state.saving && !state.loading,
                ) {
                    if (state.saving) {
                        CircularProgressIndicator()
                        Text("Salvando…")
                    } else {
                        Text(if (state.editingId == null) "Criar" else "Salvar")
                    }
                }
                TextButton(
                    onClick = {
                        showForm = false
                        onCancel()
                    },
                    enabled = !state.saving,
                ) {
                    Text("Cancelar")
                }
            }
        }

        state.users.forEach { user ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(user.username, style = MaterialTheme.typography.titleMedium)
                    if (user.displayName.isNotBlank()) {
                        Text(user.displayName)
                    }
                    val roleText = when (user.roleId) {
                        1 -> "Administrador"
                        2 -> "Trabalhador"
                        else -> user.roleName.ifBlank { "Função ${user.roleId}" }
                    }
                    Text(roleText)
                    Text(if (user.isActive == 1) "Ativo" else "Inativo")

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (state.canEdit(user)) {
                            TextButton(onClick = { onEdit(user) }) { Text("Editar") }
                        } else {
                            Text("Somente leitura", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.canToggle(user)) {
                            TextButton(onClick = { onToggle(user) }) {
                                Text(if (user.isActive == 1) "Desativar" else "Ativar")
                            }
                        }
                        if (state.canDelete(user)) {
                            TextButton(
                                onClick = {
                                    onRequestDelete(user)
                                    localDeleteTarget = user
                                },
                                enabled = !state.saving,
                            ) {
                                Text("Excluir")
                            }
                        }
                    }
                }
            }
        }

        if (!state.loading && state.users.isEmpty()) {
            Text("Nenhum usuário")
        }
    }

    val targetToDelete = state.deleteTarget ?: localDeleteTarget
    targetToDelete?.let { user ->
        if (state.canDelete(user)) {
            AlertDialog(
                onDismissRequest = {
                    localDeleteTarget = null
                    onDismissDelete()
                },
                title = { Text("Confirmar exclusão") },
                text = { Text("Excluir ${user.username}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            localDeleteTarget = null
                            onConfirmDelete()
                        },
                    ) {
                        Text("Confirmar")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            localDeleteTarget = null
                            onDismissDelete()
                        },
                    ) {
                        Text("Cancelar")
                    }
                },
            )
        }
    }
}
