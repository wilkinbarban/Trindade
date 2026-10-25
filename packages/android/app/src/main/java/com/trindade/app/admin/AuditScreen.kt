package com.trindade.app.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.contract.models.AuditResponseLogsInner
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.resolve
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Composable
private fun auditActionOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.admin_audit_all),
    "login" to stringResource(R.string.admin_audit_action_login),
    "logout" to stringResource(R.string.admin_audit_action_logout),
    "create" to stringResource(R.string.admin_audit_action_create),
    "update" to stringResource(R.string.admin_audit_action_update),
    "delete" to stringResource(R.string.admin_audit_action_delete),
    "upload" to stringResource(R.string.admin_audit_action_upload),
)

@Composable
private fun auditEntityOptions(): List<Pair<String, String>> = listOf(
    "" to stringResource(R.string.admin_audit_all),
    "auth" to stringResource(R.string.admin_audit_entity_auth),
    "report" to stringResource(R.string.admin_audit_entity_report),
    "photo" to stringResource(R.string.admin_audit_entity_photo),
    "loading" to stringResource(R.string.admin_audit_entity_loading),
    "category" to stringResource(R.string.admin_audit_entity_category),
    "task" to stringResource(R.string.admin_audit_entity_task),
    "product" to stringResource(R.string.admin_audit_entity_product),
    "driver" to stringResource(R.string.admin_audit_entity_driver),
    "vehicle" to stringResource(R.string.admin_audit_entity_vehicle),
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
    modifier: Modifier = Modifier,
) {
    var actionMenuExpanded by remember { mutableStateOf(false) }
    var entityMenuExpanded by remember { mutableStateOf(false) }
    val expandedLogIds = remember { mutableStateMapOf<Int, Boolean>() }
    val actionOptions = auditActionOptions()
    val entityOptions = auditEntityOptions()

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
            Text(stringResource(R.string.admin_section_audit), style = MaterialTheme.typography.titleLarge)
        }

        if (state.loading) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator()
                Text(stringResource(R.string.admin_loading))
            }
        }

        state.error?.let {
            Text(it.resolve(), color = MaterialTheme.colorScheme.error)
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
                    stringResource(R.string.admin_audit_restricted_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    stringResource(R.string.admin_audit_admin_only),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (state.isAdmin && !state.loading) {
            // Filters section
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val currentActionLabel = actionOptions.firstOrNull { it.first == state.actionInput }?.second
                        ?: state.actionInput.ifEmpty { stringResource(R.string.admin_audit_all) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { actionMenuExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.admin_audit_action_label, currentActionLabel))
                                Text("▼", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false },
                        ) {
                            actionOptions.forEach { (value, label) ->
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

                    val currentEntityLabel = entityOptions.firstOrNull { it.first == state.entityTypeInput }?.second
                        ?: state.entityTypeInput.ifEmpty { stringResource(R.string.admin_audit_all) }
                    Box(modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(
                            onClick = { entityMenuExpanded = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(stringResource(R.string.admin_audit_entity_label, currentEntityLabel))
                                Text("▼", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        DropdownMenu(
                            expanded = entityMenuExpanded,
                            onDismissRequest = { entityMenuExpanded = false },
                        ) {
                            entityOptions.forEach { (value, label) ->
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
                        label = { Text(stringResource(R.string.admin_audit_user_id)) },
                        placeholder = { Text(stringResource(R.string.admin_audit_user_id_placeholder)) },
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
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.admin_audit_filter))
                        }
                        OutlinedButton(
                            onClick = onClearFilters,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.admin_audit_clear))
                        }
                    }
                }
            }

            // Audit rows or empty state
            if (state.logs.isEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.admin_audit_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else {
                state.logs.forEach { log ->
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
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
                                    text = formatAuditActor(log.displayName, log.userId, stringResource(R.string.admin_audit_actor_unknown)),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = auditActionLabel(log.action),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val entityText = if (log.entityId != null) {
                                    "${auditEntityLabel(log.entityType)} #${log.entityId}"
                                } else {
                                    auditEntityLabel(log.entityType)
                                }
                                Text(
                                    text = entityText,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }

                            val isExpanded = expandedLogIds[log.id] ?: false
                            val detailsSummary = summarizeAuditDetails(log.details)
                            val isTruncated = isAuditDetailsTruncated(log.details)

                            Text(
                                text = stringResource(R.string.admin_audit_details, detailsSummary),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )

                            if (isExpanded) {
                                val fullDetails = formatAuditFullDetails(log.details)
                                if (fullDetails.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        text = fullDetails,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }

                            if (isTruncated) {
                                TextButton(
                                    onClick = { expandedLogIds[log.id] = !isExpanded },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp, minWidth = 48.dp),
                                ) {
                                    Text(if (isExpanded) stringResource(R.string.admin_audit_hide_details) else stringResource(R.string.admin_audit_show_details))
                                }
                            }

                            log.ipAddress?.let { ip ->
                                Text(
                                    text = stringResource(R.string.admin_audit_ip, ip),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            // Pagination
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val recordLabel = if (state.total == 1) {
                    stringResource(R.string.admin_audit_record_one, state.total)
                } else {
                    stringResource(R.string.admin_audit_record_other, state.total)
                }
                Text(
                    text = "$recordLabel — ${stringResource(R.string.admin_audit_page, state.page, state.totalPages)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = onPreviousPage,
                        enabled = state.canGoPrevious,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.admin_audit_previous))
                    }
                    OutlinedButton(
                        onClick = onNextPage,
                        enabled = state.canGoNext,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(stringResource(R.string.admin_audit_next))
                    }
                }
            }
        }
    }
}

internal fun formatAuditTimestamp(
    iso: String,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    if (iso.isBlank()) return "—"
    return runCatching {
        val normalized = iso.trim().replace(' ', 'T')
        val formattedIso = if (normalized.endsWith("Z") || normalized.contains('+')) normalized else normalized + "Z"
        val instant = Instant.parse(formattedIso)
        val formatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
            .withZone(zoneId)
        formatter.format(instant)
    }.getOrElse { iso }
}

internal fun formatAuditActor(
    displayName: String?,
    userId: Int?,
    unknownLabel: String = "Sem usuário",
): String {
    return when {
        !displayName.isNullOrBlank() -> displayName
        userId != null -> "#$userId"
        else -> unknownLabel
    }
}

@Composable
internal fun auditActionLabel(action: String): String = when (action.lowercase()) {
    "login" -> stringResource(R.string.admin_audit_action_login)
    "logout" -> stringResource(R.string.admin_audit_action_logout)
    "create" -> stringResource(R.string.admin_audit_action_create)
    "update" -> stringResource(R.string.admin_audit_action_update)
    "delete" -> stringResource(R.string.admin_audit_action_delete)
    "upload" -> stringResource(R.string.admin_audit_action_upload)
    else -> action
}

@Composable
internal fun auditEntityLabel(entityType: String): String = when (entityType.lowercase()) {
    "auth" -> stringResource(R.string.admin_audit_entity_auth)
    "report" -> stringResource(R.string.admin_audit_entity_report)
    "photo" -> stringResource(R.string.admin_audit_entity_photo)
    "loading" -> stringResource(R.string.admin_audit_entity_loading)
    "category" -> stringResource(R.string.admin_audit_entity_category)
    "task" -> stringResource(R.string.admin_audit_entity_task)
    "product" -> stringResource(R.string.admin_audit_entity_product)
    "driver" -> stringResource(R.string.admin_audit_entity_driver)
    "vehicle" -> stringResource(R.string.admin_audit_entity_vehicle)
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

internal fun isAuditDetailsTruncated(details: String?): Boolean {
    if (details.isNullOrBlank()) return false
    val trimmed = details.trim()
    return runCatching {
        val element = Json.parseToJsonElement(trimmed)
        if (element is JsonObject) {
            if (element.isEmpty()) return@runCatching false
            if (element.size > 2) return@runCatching true
            val preview = element.entries.take(2).joinToString(", ") { (k, v) ->
                val valueStr = if (v is JsonPrimitive && v.isString) v.content else v.toString()
                "$k: $valueStr"
            }
            preview.length > 60
        } else {
            trimmed.length > 60
        }
    }.getOrElse {
        trimmed.length > 60
    }
}

internal fun formatAuditFullDetails(details: String?): String {
    if (details.isNullOrBlank()) return ""
    val trimmed = details.trim()
    return runCatching {
        val element = Json.parseToJsonElement(trimmed)
        if (element is JsonObject) {
            if (element.isEmpty()) {
                ""
            } else {
                element.entries.joinToString("\n") { (k, v) ->
                    val valueStr = if (v is JsonPrimitive && v.isString) v.content else v.toString()
                    "$k: $valueStr"
                }
            }
        } else {
            trimmed
        }
    }.getOrElse {
        trimmed
    }
}

@Composable
fun AuditRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuditViewModel = androidx.hilt.navigation.compose.hiltViewModel(key = sessionKey),
) {
    val state by viewModel.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.load() }
    androidx.activity.compose.BackHandler(enabled = true, onBack = onBack)
    AuditScreen(
        state = state,
        onBack = onBack,
        onActionChange = viewModel::onActionChange,
        onEntityTypeChange = viewModel::onEntityTypeChange,
        onUserIdChange = viewModel::onUserIdChange,
        onApplyFilters = viewModel::applyFilters,
        onClearFilters = viewModel::clearFilters,
        onPreviousPage = viewModel::previousPage,
        onNextPage = viewModel::nextPage,
        modifier = modifier,
    )
}
