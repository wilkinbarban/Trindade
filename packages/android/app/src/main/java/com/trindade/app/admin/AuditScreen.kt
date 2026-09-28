package com.trindade.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trindade.app.contract.models.AuditResponseLogsInner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private val ACTION_OPTIONS = listOf(
    "" to "Todos",
    "login" to "Login",
    "logout" to "Logout",
    "create" to "Criação",
    "update" to "Atualização",
    "delete" to "Exclusão",
    "upload" to "Upload",
)

private val ENTITY_OPTIONS = listOf(
    "" to "Todos",
    "auth" to "Autenticação",
    "report" to "Relatório",
    "photo" to "Foto",
    "loading" to "Carregamento",
    "category" to "Categoria",
    "task" to "Tarefa",
    "product" to "Produto",
    "driver" to "Motorista",
    "vehicle" to "Veículo",
)

/**
 * Audit log management UI.
 *
 * Driven strictly by [AuditViewModel.UiState]. Admin-only surface mirroring the SPA
 * audit page: selectors for action and entity, positive user ID input, apply/clear filters,
 * 20-item paging controls, and rows with timestamp, actor, action badge, entity and
 * summarized/truncated details. Logs are never displayed during in-flight load or to unauthorized roles.
 */
@Composable
fun AuditScreen(
    state: AuditViewModel.UiState,
    onBack: () -> Unit,
    onActionChange: (String) -> Unit = {},
    onEntityTypeChange: (String) -> Unit = {},
    onUserIdChange: (String) -> Unit = {},
    onApplyFilters: () -> Unit = {},
    onClearFilters: () -> Unit = {},
    onPreviousPage: () -> Unit = {},
    onNextPage: () -> Unit = {},
    onRefresh: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var entityMenuExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("Voltar") }
                Text("Auditoria", style = MaterialTheme.typography.titleLarge)
            }
            TextButton(
                onClick = onRefresh,
                enabled = !state.loading,
            ) {
                Text("Atualizar")
            }
        }

        if (state.loading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text("Carregando…")
            }
        }

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (!state.isAdmin && !state.loading) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "Acesso Restrito",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "Esta área é exclusiva para administradores.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.isAdmin && !state.loading) {
            // Filters section
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val currentActionLabel = ACTION_OPTIONS.firstOrNull { it.first == state.actionInput }?.second
                        ?: state.actionInput.ifEmpty { "Todos" }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { actionMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Ação: $currentActionLabel")
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false },
                        ) {
                            ACTION_OPTIONS.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        actionMenuExpanded = false
                                        onActionChange(value)
                                    },
                                )
                            }
                        }
                    }

                    val currentEntityLabel = ENTITY_OPTIONS.firstOrNull { it.first == state.entityTypeInput }?.second
                        ?: state.entityTypeInput.ifEmpty { "Todos" }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { entityMenuExpanded = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Entidade: $currentEntityLabel")
                        }
                        DropdownMenu(
                            expanded = entityMenuExpanded,
                            onDismissRequest = { entityMenuExpanded = false },
                        ) {
                            ENTITY_OPTIONS.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        entityMenuExpanded = false
                                        onEntityTypeChange(value)
                                    },
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = state.userIdInput,
                        onValueChange = onUserIdChange,
                        label = { Text("ID Usuário") },
                        placeholder = { Text("Ex: 1") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(
                            onClick = onApplyFilters,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Filtrar")
                        }
                        OutlinedButton(
                            onClick = onClearFilters,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Limpar")
                        }
                    }
                }
            }

            // Audit rows or empty state
            if (state.logs.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "Nenhum log de auditoria encontrado.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                state.logs.forEach { log ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatAuditTimestamp(log.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = formatAuditActor(log.displayName, log.userId),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatAuditAction(log.action),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val entityText = if (log.entityId != null) {
                                    "${formatAuditEntity(log.entityType)} #${log.entityId}"
                                } else {
                                    formatAuditEntity(log.entityType)
                                }
                                Text(
                                    text = entityText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            val detailsSummary = summarizeAuditDetails(log.details)
                            Text(
                                text = "Detalhes: $detailsSummary",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            log.ipAddress?.let { ip ->
                                Text(
                                    text = "IP: $ip",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Pagination
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val recordWord = if (state.total == 1) "registro" else "registros"
                Text(
                    text = "${state.total} $recordWord — Página ${state.page} de ${state.totalPages}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPreviousPage,
                        enabled = state.canGoPrevious,
                    ) {
                        Text("Anterior")
                    }
                    OutlinedButton(
                        onClick = onNextPage,
                        enabled = state.canGoNext,
                    ) {
                        Text("Próximo")
                    }
                }
            }
        }
    }
}

internal fun formatAuditTimestamp(iso: String): String {
    if (iso.isBlank()) return "—"
    return runCatching {
        val normalized = iso.trim().replace(' ', 'T')
        val formattedIso = if (normalized.endsWith("Z") || normalized.contains('+')) normalized else normalized + "Z"
        val instant = Instant.parse(formattedIso)
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(ZoneId.of("UTC"))
        formatter.format(instant)
    }.getOrElse { iso }
}

internal fun formatAuditActor(displayName: String?, userId: Int?): String {
    return when {
        !displayName.isNullOrBlank() -> displayName
        userId != null -> "#$userId"
        else -> "Sem usuário"
    }
}

internal fun formatAuditAction(action: String): String = when (action.lowercase()) {
    "login" -> "Login"
    "logout" -> "Logout"
    "create" -> "Criação"
    "update" -> "Atualização"
    "delete" -> "Exclusão"
    "upload" -> "Upload"
    else -> action
}

internal fun formatAuditEntity(entityType: String): String = when (entityType.lowercase()) {
    "auth" -> "Autenticação"
    "report" -> "Relatório"
    "photo" -> "Foto"
    "loading" -> "Carregamento"
    "category" -> "Categoria"
    "task" -> "Tarefa"
    "product" -> "Produto"
    "driver" -> "Motorista"
    "vehicle" -> "Veículo"
    else -> entityType
}

internal fun summarizeAuditDetails(details: String?): String {
    if (details.isNullOrBlank()) return "—"
    val trimmed = details.trim()
    return runCatching {
        val element = Json.parseToJsonElement(trimmed)
        if (element is JsonObject) {
            if (element.isEmpty()) {
                "—"
            } else {
                val preview = element.entries.take(2).joinToString(", ") { (k, v) ->
                    val valueStr = if (v is JsonPrimitive && v.isString) v.content else v.toString()
                    "$k: $valueStr"
                }
                if (preview.length > 60) preview.take(57) + "..." else preview
            }
        } else {
            if (trimmed.length > 60) trimmed.take(57) + "..." else trimmed
        }
    }.getOrElse {
        if (trimmed.length > 60) trimmed.take(57) + "..." else trimmed
    }
}
