package com.trindade.app.stock

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.StockDayProductsInner
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.HelpSection
import com.trindade.app.ui.components.ModuleHelpButton
import com.trindade.app.ui.components.ModuleHelpDialog
import com.trindade.app.ui.components.ModuleHistoryButton
import com.trindade.app.ui.resolve
import com.trindade.app.ui.filterPhoneDigits

/**
 * The counter, held in one hand at a shelf.
 *
 * The screen draws and hands the typing over; every judgement comes from the ViewModel and, behind it, from
 * the pure rule and the server. Nothing is computed here, not even a total: the numbers shown are the ones
 * the server answered with, which is what keeps two devices from disagreeing about the same shelf.
 *
 * Below a certain width the products are cards rather than columns, because this is used standing up with a
 * phone and a table of five columns is a table nobody can read there.
 */
@Composable
fun StockCalculatorScreen(
    role: String? = null,
    // El histórico dejó de ser una pestaña: es la acción de este módulo, igual que en la web.
    onOpenHistory: () -> Unit = {},
    viewModel: StockViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val groups = viewModel.groups()
    val visibleErrors = viewModel.visibleErrors()
    val canEdit = viewModel.canEdit()
    // La pestaña que se está mostrando, y sus productos: la primera si todavía no se eligió ninguna,
    // porque el servidor ya dijo cuál viene primero en el catálogo.
    val shown = state.tab ?: groups.keys.firstOrNull()
    val active = shown?.let { groups[it] } ?: groups.values.firstOrNull()
    var showHelpDialog by remember { mutableStateOf(false) }


    LaunchedEffect(role) { viewModel.onRoleKnown(role) }
    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Las acciones en las esquinas -- el histórico a la izquierda, la ayuda a la derecha -- y la
        // frase del módulo debajo, con su propio aire: compartiendo la fila se apretaba contra los dos
        // botones y se leía como parte del control en vez de como lo que la pantalla hace.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModuleHistoryButton(onClick = onOpenHistory)
                Spacer(modifier = Modifier.weight(1f))
                ModuleHelpButton(onClick = { showHelpDialog = true })
            }
            Text(
                if (canEdit) stringResource(R.string.stock_calculator_subtitle_edit)
                else stringResource(R.string.stock_calculator_subtitle_readonly),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }

        if (state.failed) {
            Text(
                stringResource(R.string.stock_failed_to_load_count_day),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            groups.keys.forEach { category ->
                Button(
                    onClick = { viewModel.onTabChange(category) },
                    enabled = category != shown,
                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                ) {
                    val label = if (category == "Massas") stringResource(R.string.stock_category_massas)
                    else stringResource(R.string.stock_category_terceirizados)
                    Text("$label (${groups[category]?.size ?: 0})", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                }
            }
        }

        active?.forEach { product ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(product.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.stock_code_prefix, product.code),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            stringResource(R.string.stock_boxes_label, product.boxes),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.stock_units_per_box_label, product.units, product.unitsPerBox),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (canEdit && visibleErrors.contains(product.code)) {
                        Text(
                            stringResource(R.string.stock_invalid_boxes_error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (canEdit) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = state.quantities[product.code] ?: "",
                                onValueChange = { viewModel.onQuantityChange(product.code, it) },
                                singleLine = true,
                                enabled = canEdit,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .width(110.dp)
                                    // El foco se escucha en el modificador, que es donde Compose lo ofrece.
                                    .onFocusChanged { focus ->
                                        if (focus.isFocused) viewModel.onFocus(product.code) else viewModel.onBlur(product.code)
                                    },
                            )
                            Button(
                                onClick = { viewModel.addEntry(product) },
                                enabled = canEdit && !addDisabled(state.quantities[product.code] ?: ""),
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(stringResource(R.string.stock_add_button), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                            Button(
                                onClick = { viewModel.resetProduct(product) },
                                enabled = canEdit,
                                modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                Text(stringResource(R.string.stock_reset_button), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.stock_day_total), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(
                    R.string.stock_day_total_format,
                    state.day?.totalBoxes ?: 0,
                    state.day?.totalUnits ?: 0,
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }

        // O resumo do dia fica logo abaixo da calculadora: é o que o operador confere antes de enviar.
        StockCollectiveSummary(products = state.day?.let { stockCollectiveSummary(it) } ?: emptyList())

        OutlinedTextField(
            value = state.number,
            onValueChange = { viewModel.onNumberChange(filterPhoneDigits(it)) },
            visualTransformation = BrazilianPhoneVisualTransformation(),
            label = { Text(stringResource(R.string.whatsapp_phone_label)) },
            placeholder = { Text(stringResource(R.string.whatsapp_phone_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                Text(stringResource(R.string.whatsapp_message_label), style = MaterialTheme.typography.bodySmall)
                Text(viewModel.message(), style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            onClick = {
                val target = viewModel.whatsappLink()
                if (target == null) {
                    viewModel.onNote(R.string.stock_whatsapp_invalid_phone)
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    viewModel.onNote(R.string.stock_whatsapp_opening)
                }
            },
            enabled = viewModel.hasSomethingToSend() && state.number.isNotBlank(),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.whatsapp_send_button), style = MaterialTheme.typography.labelLarge)
        }

        Button(
            onClick = {
                // El destino es el buzon de la planta; el correo del autor no se usa aca.
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(viewModel.mailtoLink())))
                viewModel.onNote(R.string.stock_email_opening)
            },
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.stock_send_email_button), style = MaterialTheme.typography.labelLarge)
        }

        state.note?.let { note ->
            Text(note.resolve(), style = MaterialTheme.typography.bodySmall)
        }

        if (showHelpDialog) {
            ModuleHelpDialog(
                title = stringResource(R.string.help_stock_title),
                description = stringResource(R.string.help_stock_desc),
                sections = listOf(
                    HelpSection(
                        title = stringResource(R.string.help_stock_counting_title),
                        description = stringResource(R.string.help_stock_counting_desc),
                    ),
                    HelpSection(
                        title = stringResource(R.string.help_stock_permissions_title),
                        description = stringResource(R.string.help_stock_permissions_desc),
                    ),
                ),
                onDismissRequest = { showHelpDialog = false },
            )
        }
    }
}

/**
 * O Resumo Coletivo do Dia: o que foi contado, produto por produto, com os totais que o servidor somou.
 *
 * Sem filtro por operador, ao contrário do resumo de massas: uma contagem de estoque é do galpão, não de
 * quem passou por ele. O desenho, porém, é o mesmo daquele resumo -- cartelas âmbar com o nome, o un/cx e
 * as duas etiquetas -- porque é o mesmo número lido no mesmo lugar.
 */
@Composable
internal fun StockCollectiveSummary(
    products: List<StockDayProductsInner>,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.stock_collective_summary),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.stock_collective_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (products.isEmpty()) {
                Text(
                    text = stringResource(R.string.stock_no_products_counted),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                products.forEach { product ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = product.name,
                                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Text(
                                    text = stringResource(R.string.stock_units_per_box_short, product.unitsPerBox),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.08f), CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.stock_summary_boxes, product.boxes),
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .background(MaterialTheme.colorScheme.primary, CircleShape)
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.stock_summary_units, product.units),
                                        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
                                        color = MaterialTheme.colorScheme.onPrimary,
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
