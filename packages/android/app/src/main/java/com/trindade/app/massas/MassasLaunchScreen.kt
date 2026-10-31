package com.trindade.app.massas

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trindade.app.R
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.AdminSecondaryButton
import com.trindade.app.ui.components.HelpSection
import com.trindade.app.ui.components.ModuleHelpButton
import com.trindade.app.ui.components.ModuleHelpDialog
import com.trindade.app.ui.components.ModuleHistoryButton
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.resolve
import com.trindade.app.ui.filterPhoneDigits
import androidx.compose.ui.platform.testTag

@Composable
fun MassasLaunchScreen(
    viewModel: MassasLaunchViewModel,
    onNavigateToHistory: () -> Unit,
    onBackToHistory: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onScreenActive(true)
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onScreenActive(false)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.onScreenActive(false)
        }
    }

    val helpSections = listOf(
        HelpSection(
            title = stringResource(R.string.help_massas_step1_title),
            description = stringResource(R.string.help_massas_step1_desc),
        ),
        HelpSection(
            title = stringResource(R.string.help_massas_step2_title),
            description = stringResource(R.string.help_massas_step2_desc),
        ),
        HelpSection(
            title = stringResource(R.string.help_massas_step3_title),
            description = stringResource(R.string.help_massas_step3_desc),
        ),
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Encabezado: sus acciones en las esquinas, sin repetir el título que la pestaña ya dice, y la
        // frase del módulo debajo de ellas. El "Voltar" sólo aparece en el día leído desde el histórico,
        // que es la subpantalla de este módulo.
        // The actions and the module's line are one block, 4dp apart, the same block the other three
        // modules draw: the line belongs to the header, not to the gap between the header and the next card.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.isReadOnly && onBackToHistory != null) {
                    NavigationActionButton(
                        onClick = onBackToHistory,
                        text = stringResource(R.string.report_back),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (!state.isReadOnly) {
                    ModuleHistoryButton(onClick = onNavigateToHistory)
                }
                Spacer(modifier = Modifier.weight(1f))
                ModuleHelpButton(onClick = { viewModel.toggleHelpDialog(true) })
            }
            // The tagline is the module's own line, not a read-only notice: the date and the read-only
            // state are already in the card below, and swapping the description for the date left the
            // header saying two different kinds of thing depending on who was looking at it.
            Text(
                text = stringResource(R.string.massas_tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Banners de Notificación / Error
        if (state.noticeMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "✓ " + (state.noticeMessage?.resolve() ?: ""),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.clearNotice() },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.massas_close),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }

        if (state.errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⚠ " + (state.errorMessage?.resolve() ?: ""),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { viewModel.clearError() },
                        modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.massas_close),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        // Fila de Fecha y Sincronización
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.massas_production_date, brazilianDate(state.date)),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(
                            enabled = !state.isLoading,
                            onClick = { viewModel.refresh() },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color.Transparent,
                        border = BorderStroke(
                            1.dp,
                            if (!state.isLoading) MaterialTheme.colorScheme.outline
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
                        ),
                        contentColor = if (!state.isLoading) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                    ) {
                        Row(
                            modifier = Modifier
                                .heightIn(min = 32.dp)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (state.isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.massas_sync_now),
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                )
                            }
                        }
                    }
                }
            }
        }

        // Sincronización con el ERP: el día de la planta, leído en un viaje y guardado en el backend.
        // Se dibuja dentro de la misma tarjeta de la fecha porque es la fecha lo que se sincroniza, y con
        // la hora del último intento al lado para que "ya lo hice" sea verificable y no un recuerdo.
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = state.aeroSyncedAt?.let { stringResource(R.string.massas_synced_at, it) }
                        ?: stringResource(R.string.massas_never_synced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                AdminSecondaryButton(
                    text = stringResource(R.string.massas_sync_erp),
                    onClick = { viewModel.syncAeroDay() },
                    enabled = state.aeroReady && !state.isSyncingAero,
                    modifier = Modifier.testTag(MASSAS_SYNC_TAG),
                )
            }
        }

        // La tabla unificada: lo lanzado aquí, lo que el ERP ya tiene y lo que sólo está en el ERP.
        MassasUnifiedTable(
            state = state,
            onSend = viewModel::sendPendingLaunch,
            onAcknowledge = viewModel::acknowledgeLaunch,
            onImport = viewModel::importErpSeat,
            onCreateOperator = viewModel::createOperatorFromSeat,
        )

        if (!state.isReadOnly) {
            // BLOQUE 1: Lançar Produção do Dia (Cálculo e Inputs)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.massas_launch_by_boxes),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    STANDARD_MASSAS.forEach { product ->
                        val boxesStr = state.quantities[product.code] ?: ""
                        val boxes = boxesStr.toIntOrNull() ?: 0
                        val calculatedUnits = boxes * product.unitsPerBox

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    color = if (boxes > 0) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = product.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = "${product.unitsPerBox} un/cx",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (calculatedUnits > 0) {
                                        Text(
                                            text = "= $calculatedUnits un",
                                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = boxesStr,
                                onValueChange = { viewModel.onQuantityChange(product.code, it) },
                                placeholder = { Text("0", style = MaterialTheme.typography.bodyMedium) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(52.dp),
                                shape = RoundedCornerShape(8.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    val hasAnyQuantity = state.quantities.values.any { (it.toIntOrNull() ?: 0) > 0 }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.clearQuantities() },
                            enabled = hasAnyQuantity && !state.isSubmitting,
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp),
                        ) {
                            Text(stringResource(R.string.massas_clear), style = MaterialTheme.typography.labelLarge)
                        }

                        Button(
                            onClick = { viewModel.requestLaunchConfirmation() },
                            enabled = hasAnyQuantity && !state.isSubmitting,
                            modifier = Modifier
                                .weight(2f)
                                .defaultMinSize(minHeight = 48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        ) {
                            if (state.isSubmitting) {
                                CircularProgressIndicator(
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(R.string.massas_submitting))
                            } else {
                                Text(
                                    text = stringResource(R.string.massas_submit),
                                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                        }
                    }
                }
            }
        }

        // BLOQUE 2: Lançamentos Individuais do Dia (filtro por trabalhador no topo)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Título com foco em lançamentos de trabalhadores
                Text(
                    text = stringResource(R.string.massas_recent_launches),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val launches = state.dayView?.launches ?: emptyList()
                val operators = remember(launches) { extractOperators(launches) }

                // 1. Chips de filtro por operador no topo
                if (operators.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val isAllSelected = state.selectedOperatorId == null
                            MassasFilterChip(
                                selected = isAllSelected,
                                onClick = { viewModel.selectOperator(null) },
                                label = stringResource(R.string.massas_all_workers),
                            )

                            operators.forEach { op ->
                                val isSelected = state.selectedOperatorId == op.id
                                MassasFilterChip(
                                    selected = isSelected,
                                    onClick = { viewModel.selectOperator(op.id) },
                                    label = "👤 ${op.name}",
                                )
                            }
                        }

                        // 2. Subtotal do operador selecionado
                        if (state.selectedOperatorId != null) {
                            val subtotal = calculateOperatorSubtotal(launches, state.selectedOperatorId)
                            val opName = operators.find { it.id == state.selectedOperatorId }?.name ?: ""
                            if (subtotal != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            text = stringResource(R.string.massas_worker_subtotal, opName),
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                        Text(
                                            text = "${subtotal.first} cx (${subtotal.second} un)",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. Lista de lançamentos individuais (máxima prioridade visual)
                val displayedLaunches = filterLaunchesByOperator(launches, state.selectedOperatorId)

                if (displayedLaunches.isEmpty()) {
                    Text(
                        text = stringResource(R.string.massas_no_launches_today),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // Lo que el ERP ya asentó no se edita ni se borra: la fila lo dice en lugar de ofrecer los
                    // botones deshabilitados, porque un botón apagado sin motivo se lee como un error de la app.
                    val settledIds = reconciledLaunchIds(state.unifiedRows)

                    displayedLaunches.forEach { launch ->
                        val reconciled = launch.id in settledIds
                        val canEdit = viewModel.canEdit(launch)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = launch.productName,
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    )
                                    Text(
                                        text = "${launch.boxes} cx (${launch.units} un)",
                                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    val operatorName = launch.displayName.ifBlank { launch.username }
                                    Text(
                                        text = stringResource(R.string.massas_by_operator, operatorName),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    if (reconciled) {
                                        Text(
                                            text = stringResource(R.string.massas_state_reconciled),
                                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                    } else if (canEdit) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(
                                                onClick = { viewModel.startEdit(launch) },
                                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                            ) {
                                                Text(stringResource(R.string.massas_edit), style = MaterialTheme.typography.labelMedium)
                                            }
                                            TextButton(
                                                onClick = { viewModel.promptDelete(launch.id) },
                                                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                            ) {
                                                Text(
                                                    stringResource(R.string.massas_delete),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

            }
        }

        // BLOQUE 3: Resumo Coletivo do Dia (segue o filtro de trabalhador e destaca os totais)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.massas_collective_summary),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val catalog = state.dayView?.products ?: emptyList()
                val allLaunches = state.dayView?.launches ?: emptyList()
                val visibleLaunches = filterLaunchesByOperator(allLaunches, state.selectedOperatorId)
                val collectiveProducts = remember(visibleLaunches, catalog) {
                    groupProductsFromLaunches(visibleLaunches, catalog)
                }

                if (collectiveProducts.isEmpty()) {
                    Text(
                        text = stringResource(R.string.massas_no_launches_today),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    collectiveProducts.forEach { product ->
                        val workerNames = productWorkerNames(visibleLaunches, product.code)

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = product.name,
                                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = "${product.unitsPerBox} un/cx",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = "${product.totalBoxes} cx",
                                                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Box(
                                            modifier = Modifier
                                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                                .padding(horizontal = 10.dp, vertical = 4.dp),
                                        ) {
                                            Text(
                                                text = "${product.totalUnits} un",
                                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                                color = MaterialTheme.colorScheme.onPrimary,
                                            )
                                        }
                                    }
                                }

                                if (workerNames.isNotEmpty()) {
                                    Text(
                                        text = "👤 ${workerNames.joinToString(", ")}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // BLOQUE 4: Compartilhar no WhatsApp
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.massas_whatsapp_share),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                OutlinedTextField(
                    value = state.phone,
                    onValueChange = { viewModel.onPhoneChange(filterPhoneDigits(it)) },
                    visualTransformation = BrazilianPhoneVisualTransformation(),
                    label = { Text(stringResource(R.string.whatsapp_phone_label)) },
                    placeholder = { Text(stringResource(R.string.whatsapp_phone_placeholder)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Pré-visualização da Mensagem
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.share_preview_label),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = viewModel.getShareText(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val whatsappLink = viewModel.getWhatsAppLink()
                    Button(
                        onClick = {
                            if (whatsappLink != null) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(whatsappLink)))
                            }
                        },
                        enabled = whatsappLink != null,
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = "WhatsApp",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val text = viewModel.getShareText()
                            if (text.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(text))
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .defaultMinSize(minHeight = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.massas_copy_report),
                            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }

    // Modal de Ayuda Contextual
    if (state.showHelpDialog) {
        ModuleHelpDialog(
            title = stringResource(R.string.help_massas_title),
            subtitle = stringResource(R.string.massas_tagline),
            description = stringResource(R.string.help_massas_desc),
            sections = helpSections,
            onDismissRequest = { viewModel.toggleHelpDialog(false) },
        )
    }

    // Diálogo de Confirmación de Borrado
    if (state.deleteConfirmLaunchId != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDelete() },
            title = { Text(stringResource(R.string.massas_delete_confirm_title)) },
            text = { Text(stringResource(R.string.massas_delete_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.deleteLaunch() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) {
                    Text(stringResource(R.string.massas_delete_confirm_action), color = MaterialTheme.colorScheme.onError)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDelete() }) {
                    Text(stringResource(R.string.massas_cancel))
                }
            },
        )
    }

    // Diálogo Modal de Edição de Lançamento (Rediseño amplio con cálculo de unidades)
    if (state.editingLaunchId != null) {
        val launchId = state.editingLaunchId
        val editingLaunch = state.dayView?.launches?.find { it.id == launchId }
        val productName = editingLaunch?.productName ?: "Massa"
        val unitsPerBox = state.dayView?.products?.find { it.code == editingLaunch?.productCode }?.unitsPerBox
            ?: STANDARD_MASSAS.find { it.code == editingLaunch?.productCode }?.unitsPerBox
            ?: (if (editingLaunch != null && editingLaunch.boxes > 0) editingLaunch.units / editingLaunch.boxes else 10)
        val editedBoxes = state.editingBoxes.toIntOrNull() ?: 0
        val calculatedUnits = editedBoxes * unitsPerBox

        AlertDialog(
            onDismissRequest = { viewModel.cancelEdit() },
            title = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.massas_edit_title),
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    )
                    Text(
                        text = productName,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedTextField(
                        value = state.editingBoxes,
                        onValueChange = { viewModel.onEditingBoxesChange(it) },
                        label = { Text(stringResource(R.string.massas_boxes_quantity)) },
                        placeholder = { Text("0") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (calculatedUnits > 0) {
                        Text(
                            text = stringResource(R.string.massas_calculated_units_format, calculatedUnits, unitsPerBox),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.saveEdit() },
                    enabled = !state.isSubmitting && (state.editingBoxes.toIntOrNull() ?: 0) > 0,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(stringResource(R.string.massas_edit_save), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.cancelEdit() }) {
                    Text(stringResource(R.string.massas_cancel))
                }
            },
        )
    }

    // Diálogo de Confirmação Prévio ao Lançamento
    if (state.showConfirmLaunchDialog) {
        val totalBoxes = state.confirmItems.sumOf { it.boxes }
        val totalUnits = state.confirmItems.sumOf { it.units }

        AlertDialog(
            onDismissRequest = { viewModel.dismissConfirmLaunch() },
            title = {
                Text(
                    text = "🍝 ${stringResource(R.string.massas_confirm_title)}",
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.massas_confirm_subtitle),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.confirmItems.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "📦 ${item.productName ?: stringResource(R.string.massas_product_fallback, item.productCode)}",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = "${item.boxes} cx (${item.units} un)",
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = stringResource(R.string.massas_total_to_launch),
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                        )
                        Text(
                            text = "$totalBoxes cx ($totalUnits un)",
                            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.ExtraBold),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmLaunch() },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                ) {
                    Text(stringResource(R.string.massas_confirm_action), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissConfirmLaunch() }) {
                    Text(stringResource(R.string.massas_cancel))
                }
            },
        )
    }

    // Diálogo Modal de Notificação de Resultado
    if (state.resultDialogMessage != null) {
        val isSuccess = state.resultDialogIsSuccess
        AlertDialog(
            onDismissRequest = { viewModel.dismissResultDialog() },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(text = if (isSuccess) "✅" else "❌")
                    Text(
                        text = stringResource(
                            if (isSuccess) R.string.massas_success_title else R.string.massas_error_title
                        ),
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            text = {
                Text(
                    text = state.resultDialogMessage?.resolve() ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissResultDialog() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                        contentColor = if (isSuccess) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onError,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .defaultMinSize(minHeight = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.massas_close),
                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    )
                }
            },
        )
    }
}

/**
 * Filter chip matching Material 3 token roles with an outer >=48dp touch target and inner 32dp pill.
 */
@Composable
private fun MassasFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 32.dp)
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}
