package com.trindade.app.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.AuditResponseLogsInner
import com.trindade.app.ui.components.NavigationActionButton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

object AuditFilterOptions {
    val ACTIONS = listOf(
        "" to "Todos",
        "login" to "Login",
        "logout" to "Logout",
        "create" to "Criação",
        "update" to "Atualização",
        "delete" to "Exclusão",
        "upload" to "Upload",
    )

    val ENTITY_TYPES = listOf(
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
}

fun formatAuditAction(action: String): String =
    AuditFilterOptions.ACTIONS.firstOrNull { it.first == action }?.second
        ?: action.replaceFirstChar { it.uppercase() }

fun formatAuditEntity(entityType: String): String =
    AuditFilterOptions.ENTITY_TYPES.firstOrNull { it.first == entityType }?.second
        ?: entityType.replaceFirstChar { it.uppercase() }

fun formatAuditUser(displayName: String?, userId: Int?): String =
    when {
        !displayName.isNullOrBlank() -> displayName
        userId != null -> "#$userId"
        else -> "—"
    }

fun formatAuditDate(iso: String): String {
    return runCatching {
        val normalized = iso.replace(' ', 'T')
        val instant = java.time.Instant.parse(if (normalized.endsWith("Z")) normalized else "${normalized}Z")
        val local = java.time.LocalDateTime.ofInstant(instant, java.time.ZoneId.of("America/Sao_Paulo"))
        val formatter = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
        local.format(formatter)
    }.getOrElse { iso }
}

@Composable
fun AuditActionBadge(action: String) {
    val (bgColor, textColor) = when (action) {
        "login" -> Color(0xFFD1F2D9) to Color(0xFF1B5E20)
        "logout" -> Color(0xFFE0E0E0) to Color(0xFF424242)
        "create", "upload" -> Color(0xFFD0E1FD) to Color(0xFF0D47A1)
        "update" -> Color(0xFFFFF3CD) to Color(0xFF856404)
        "delete" -> Color(0xFFF8D7DA) to Color(0xFF721C24)
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = Modifier
            .background(color = bgColor, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            text = formatAuditAction(action),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
    }
}

@Composable
fun AuditDetailsContent(details: String?, expanded: Boolean) {
    if (details.isNullOrBlank()) {
        Text(
            text = "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }

    val parsedEntries: List<Pair<String, String>> = remember(details) {
        runCatching {
            val jsonElement = Json.parseToJsonElement(details)
            if (jsonElement is JsonObject) {
                jsonElement.entries.map { (key, value) ->
                    val cleanValue = if (value is JsonPrimitive && value.isString) {
                        value.content
                    } else {
                        value.toString()
                    }
                    key to cleanValue
                }
            } else {
                listOf("detalhes" to details)
            }
        }.getOrElse {
            listOf("detalhes" to details)
        }
    }

    if (expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            parsedEntries.forEach { (k, v) ->
                Text(
                    text = "$k: $v",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    } else {
        val previewText = parsedEntries.take(2).joinToString(", ") { "${it.first}: ${it.second}" }
        Text(
            text = previewText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun AuditScreen(
    state: AuditViewModel.UiState,
    onBack: () -> Unit,
    onActionInputChanged: (String) -> Unit,
    onEntityTypeInputChanged: (String) -> Unit,
    onUserIdInputChanged: (String) -> Unit,
    onApplyFilters: () -> Unit,
    onClearFilters: () -> Unit,
    onPreviousPage: () -> Unit,
    onNextPage: () -> Unit,
    onToggleDetails: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var actionMenuOpen by remember { mutableStateOf(false) }
    var entityMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavigationActionButton(onClick = onBack) {
                Text(stringResource(R.string.report_back))
            }
            Text(
                text = stringResource(R.string.audit_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        if (!state.isAdmin) {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.audit_access_denied_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = stringResource(R.string.audit_access_denied_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            return
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

        // Filters Section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.audit_filter_title),
                    style = MaterialTheme.typography.titleMedium,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Action dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { actionMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.audit_filter_action_value,
                                    formatAuditAction(state.actionInput),
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = actionMenuOpen,
                            onDismissRequest = { actionMenuOpen = false },
                        ) {
                            AuditFilterOptions.ACTIONS.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        actionMenuOpen = false
                                        onActionInputChanged(key)
                                    },
                                )
                            }
                        }
                    }

                    // Entity dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        OutlinedButton(
                            onClick = { entityMenuOpen = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(
                                    R.string.audit_filter_entity_value,
                                    formatAuditEntity(state.entityTypeInput),
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        DropdownMenu(
                            expanded = entityMenuOpen,
                            onDismissRequest = { entityMenuOpen = false },
                        ) {
                            AuditFilterOptions.ENTITY_TYPES.forEach { (key, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        entityMenuOpen = false
                                        onEntityTypeInputChanged(key)
                                    },
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = state.userIdInput,
                        onValueChange = onUserIdInputChanged,
                        label = { Text(stringResource(R.string.audit_filter_user_id)) },
                        placeholder = { Text(stringResource(R.string.audit_filter_user_id_placeholder)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )

                    Button(onClick = onApplyFilters) {
                        Text(stringResource(R.string.audit_filter_apply))
                    }

                    OutlinedButton(onClick = onClearFilters) {
                        Text(stringResource(R.string.audit_filter_clear))
                    }
                }
            }
        }

        // Logs Display Area
        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else if (state.logs.isEmpty()) {
            Text(
                text = stringResource(R.string.audit_empty),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.logs.forEach { log ->
                    val isExpanded = state.expandedLogId == log.id
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggleDetails(log.id) },
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = formatAuditDate(log.createdAt),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                AuditActionBadge(action = log.action)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = stringResource(
                                        R.string.audit_item_user,
                                        formatAuditUser(log.displayName, log.userId),
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )

                                Text(
                                    text = stringResource(
                                        R.string.audit_item_entity,
                                        formatAuditEntity(log.entityType) + (log.entityId?.let { " (#$it)" } ?: ""),
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            log.ipAddress?.let { ip ->
                                Text(
                                    text = stringResource(R.string.audit_item_ip, ip),
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    text = stringResource(R.string.audit_item_details, ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                AuditDetailsContent(details = log.details, expanded = isExpanded)
                            }

                            if (!log.details.isNullOrBlank()) {
                                Text(
                                    text = stringResource(
                                        if (isExpanded) R.string.audit_details_collapse else R.string.audit_details_expand,
                                    ),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            // Pagination Controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.audit_pagination_summary,
                        state.total,
                        state.page,
                        state.totalPages,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onPreviousPage,
                        enabled = state.canGoPrevious,
                    ) {
                        Text(stringResource(R.string.audit_pagination_prev))
                    }

                    OutlinedButton(
                        onClick = onNextPage,
                        enabled = state.canGoNext,
                    ) {
                        Text(stringResource(R.string.audit_pagination_next))
                    }
                }
            }
        }
    }
}

@Composable
fun AuditRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuditViewModel = hiltViewModel(key = sessionKey),
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    BackHandler(enabled = true) {
        if (!viewModel.handleBack()) {
            onBack()
        }
    }

    AuditScreen(
        state = state,
        onBack = {
            if (!viewModel.handleBack()) {
                onBack()
            }
        },
        onActionInputChanged = viewModel::onActionInputChanged,
        onEntityTypeInputChanged = viewModel::onEntityTypeInputChanged,
        onUserIdInputChanged = viewModel::onUserIdInputChanged,
        onApplyFilters = viewModel::applyFilters,
        onClearFilters = viewModel::clearFilters,
        onPreviousPage = viewModel::goToPreviousPage,
        onNextPage = viewModel::goToNextPage,
        onToggleDetails = viewModel::toggleDetails,
        modifier = modifier,
    )
}
