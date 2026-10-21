package com.trindade.app.massas

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trindade.app.R
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.HelpSection
import com.trindade.app.ui.components.ModuleHelpButton
import com.trindade.app.ui.components.ModuleHelpDialog
import com.trindade.app.ui.filterPhoneDigits

@Composable
fun MassasLaunchScreen(
    viewModel: MassasLaunchViewModel,
    onNavigateToHistory: () -> Unit,
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
        // Encabezado con título y botones de acción
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.massas_title),
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.massas_tagline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onNavigateToHistory,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(36.dp),
                ) {
                    Text(
                        text = "Histórico",
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
                ModuleHelpButton(onClick = { viewModel.toggleHelpDialog(true) })
            }
        }

        // Banners de Notificación / Error
        if (state.noticeMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFBBF7D0), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "✓ " + (state.noticeMessage ?: ""),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFF166534),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.clearNotice() }) {
                        Text("Fechar", color = Color(0xFF166534))
                    }
                }
            }
        }

        if (state.errorMessage != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF2F2)),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⚠ " + (state.errorMessage ?: ""),
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                        color = Color(0xFF991B1B),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.clearError() }) {
                        Text("Fechar", color = Color(0xFF991B1B))
                    }
                }
            }
        }

        // Fila de Fecha y Sincronización
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Data de Produção: ${brazilianDate(state.date)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    onClick = { viewModel.refresh() },
                    enabled = !state.isLoading,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp),
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Atualizar", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        // BLOQUE 1: Lançar Produção do Dia (Cálculo e Inputs)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Lançar Produção por Caixas",
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
                                color = if (boxes > 0) Color(0xFFF0FDF4) else Color.Transparent,
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
                                        color = Color(0xFF16A34A),
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
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Text("Limpar", style = MaterialTheme.typography.labelLarge)
                    }

                    Button(
                        onClick = { viewModel.requestLaunchConfirmation() },
                        enabled = hasAnyQuantity && !state.isSubmitting,
                        modifier = Modifier.weight(2f),
                        shape = RoundedCornerShape(10.dp),
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

        // BLOQUE 2: Resumo Coletivo do Dia (Otimizado para Mobile)
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.massas_collective_summary),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // Totales consolidados (Caixas e Unidades)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFEFF6FF)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.massas_total_boxes),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFF1E40AF),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.dayView?.totalBoxes ?: 0} cx",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF1E3A8A),
                            )
                        }
                    }

                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFF0FDF4)),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = stringResource(R.string.massas_total_units),
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                color = Color(0xFF166534),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${state.dayView?.totalUnits ?: 0} un",
                                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                                color = Color(0xFF14532D),
                            )
                        }
                    }
                }

                // Lista de produtos lançados com unidades acumuladas
                val launchedProducts = state.dayView?.products?.filter { it.totalUnits > 0 } ?: emptyList()
                if (launchedProducts.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF9FAFB), RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        launchedProducts.forEach { product ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = product.name,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium),
                                    color = Color(0xFF374151),
                                )
                                Text(
                                    text = "${product.totalBoxes} cx  (${product.totalUnits} un)",
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                    color = Color(0xFF111827),
                                )
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Lançamentos Individuais
                Text(
                    text = stringResource(R.string.massas_recent_launches),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                val launches = state.dayView?.launches ?: emptyList()
                val operators = remember(launches) { extractOperators(launches) }

                // Chips de filtro por operador
                if (operators.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            val isAllSelected = state.selectedOperatorId == null
                            Button(
                                onClick = { viewModel.selectOperator(null) },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isAllSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isAllSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                shape = RoundedCornerShape(20.dp),
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp),
                            ) {
                                Text(
                                    text = stringResource(R.string.massas_all_workers),
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }

                            operators.forEach { op ->
                                val isSelected = state.selectedOperatorId == op.id
                                Button(
                                    onClick = { viewModel.selectOperator(op.id) },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                    ),
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp),
                                ) {
                                    Text(
                                        text = "👤 ${op.name}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                                    )
                                }
                            }
                        }

                        // Subtotal do operador selecionado
                        if (state.selectedOperatorId != null) {
                            val subtotal = calculateOperatorSubtotal(launches, state.selectedOperatorId)
                            val opName = operators.find { it.id == state.selectedOperatorId }?.name ?: ""
                            if (subtotal != null) {
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                                    shape = RoundedCornerShape(8.dp),
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
                                            color = Color(0xFF92400E),
                                        )
                                        Text(
                                            text = "${subtotal.first} cx (${subtotal.second} un)",
                                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.ExtraBold),
                                            color = Color(0xFF78350F),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                val displayedLaunches = filterLaunchesByOperator(launches, state.selectedOperatorId)

                if (displayedLaunches.isEmpty()) {
                    Text(
                        text = stringResource(R.string.massas_no_launches_today),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    val currentUserId = state.currentUser?.id
                    val isAdmin = state.currentUser?.role == "Administrador"

                    displayedLaunches.forEach { launch ->
                        val canEdit = canEditOrDeleteLaunch(launch.userId, currentUserId, isAdmin)
                        val isEditingThis = state.editingLaunchId == launch.id

                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
                            shape = RoundedCornerShape(10.dp),
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
                                        text = "Por: $operatorName",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )

                                    if (canEdit && !isEditingThis) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            TextButton(onClick = { viewModel.startEdit(launch) }) {
                                                Text("Editar", style = MaterialTheme.typography.labelSmall)
                                            }
                                            TextButton(onClick = { viewModel.promptDelete(launch.id) }) {
                                                Text(
                                                    "Excluir",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                }

                                if (isEditingThis) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        OutlinedTextField(
                                            value = state.editingBoxes,
                                            onValueChange = { viewModel.onEditingBoxesChange(it) },
                                            label = { Text("Caixas") },
                                            singleLine = true,
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier
                                                .width(100.dp)
                                                .height(52.dp),
                                        )
                                        Button(
                                            onClick = { viewModel.saveEdit() },
                                            enabled = !state.isSubmitting && (state.editingBoxes.toIntOrNull() ?: 0) > 0,
                                            shape = RoundedCornerShape(8.dp),
                                        ) {
                                            Text(stringResource(R.string.massas_edit_save), style = MaterialTheme.typography.labelSmall)
                                        }
                                        TextButton(onClick = { viewModel.cancelEdit() }) {
                                            Text(stringResource(R.string.massas_cancel), style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // BLOQUE 3: Compartilhar no WhatsApp
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp)),
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
                    label = { Text("Coloque o WhatsApp") },
                    placeholder = { Text("(11) 9XXXX-XXXX") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Pré-visualização da Mensagem
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "Pré-visualização da Mensagem",
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
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
                    ) {
                        Text(
                            text = "WhatsApp",
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                            color = Color.White,
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            val text = viewModel.getShareText()
                            if (text.isNotBlank()) {
                                clipboardManager.setText(AnnotatedString(text))
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
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
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        state.confirmItems.forEach { item ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "📦 ${item.productName}",
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
                    text = state.resultDialogMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.dismissResultDialog() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isSuccess) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.massas_close),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
        )
    }
}
