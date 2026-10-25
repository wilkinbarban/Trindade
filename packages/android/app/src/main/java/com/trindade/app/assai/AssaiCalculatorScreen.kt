package com.trindade.app.assai

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.HelpSection
import com.trindade.app.ui.components.ModuleHelpButton
import com.trindade.app.ui.components.ModuleHelpDialog
import com.trindade.app.ui.filterPhoneDigits

/**
 * The Assaí order, typed standing at a shelf.
 *
 * The screen draws and hands the typing over; every judgement comes from the ViewModel and, behind it,
 * from the pure rule. The warning of a product appears only when its field has been left, which is the
 * same decision the web makes, and the share button stays off while the order is inexact: an order that
 * cannot be served is worse than no order.
 */
@Composable
fun AssaiCalculatorScreen(
    viewModel: AssaiViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val lines = viewModel.lines()
    val visibleErrors = viewModel.visibleErrors()
    val link = viewModel.whatsappLink()
    var showHelpDialog by remember { mutableStateOf(false) }

    val invalidPhoneNote = stringResource(R.string.stock_whatsapp_invalid_phone)
    val openingWhatsappNote = stringResource(R.string.stock_whatsapp_opening)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // La raíz del módulo no lleva "Voltar" ni el título que la pestaña ya dice: el nombre del módulo
        // con su ayuda en la esquina de cierre, y la explicación debajo. El título sí se dibuja acá porque
        // la fila de pestañas lo abrevia, y esta es la única línea que dice el nombre completo.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.assai_header_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.weight(1f),
                )
                ModuleHelpButton(onClick = { showHelpDialog = true })
            }
            Text(
                stringResource(R.string.assai_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                lines.forEach { line ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(line.product, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.assai_units_per_box, line.unitsPerBox) +
                                    if (line.filled && line.exact) stringResource(R.string.assai_boxes_count, line.boxes) else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            if (visibleErrors.contains(line.product)) {
                                Text(
                                    stringResource(R.string.assai_invalid_units_error),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        OutlinedTextField(
                            value = state.quantities[line.product] ?: "",
                            onValueChange = { viewModel.onQuantityChange(line.product, it) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier
                                .width(110.dp)
                                // El foco se escucha en el modificador: dentro del campo solo se
                                // informa lo que se escribe, y quien decide si el cartel va o no es
                                // el ViewModel, que sabe si el campo fue dejado.
                                .onFocusChanged { focus ->
                                    if (focus.isFocused) viewModel.onFocus(line.product) else viewModel.onBlur(line.product)
                                },
                        )
                    }
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.assai_weekday_label), style = MaterialTheme.typography.bodySmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.selectedDay == "Terça-feira",
                        onClick = { viewModel.onDaySelect("Terça-feira") },
                        label = { Text(stringResource(R.string.weekday_tuesday), style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    )
                    FilterChip(
                        selected = state.selectedDay == "Quinta-feira",
                        onClick = { viewModel.onDaySelect("Quinta-feira") },
                        label = { Text(stringResource(R.string.weekday_thursday), style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = state.selectedDay == "Sexta-feira",
                        onClick = { viewModel.onDaySelect("Sexta-feira") },
                        label = { Text(stringResource(R.string.weekday_friday), style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    )
                    FilterChip(
                        selected = state.selectedDay == "Sábado",
                        onClick = { viewModel.onDaySelect("Sábado") },
                        label = { Text(stringResource(R.string.weekday_saturday), style = MaterialTheme.typography.labelMedium) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primary,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                        modifier = Modifier.weight(1f).defaultMinSize(minHeight = 48.dp),
                    )
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.assai_order_total), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.assai_total_boxes_format, viewModel.totalBoxes()), style = MaterialTheme.typography.titleMedium)
        }

        OutlinedTextField(
            value = state.freeNumber,
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
                // The link is the canonical endpoint, and the intent only names the address: which
                // application answers it stays the system's decision.
                val target = link
                if (target == null) {
                    viewModel.onNote(invalidPhoneNote)
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    viewModel.onNote(openingWhatsappNote)
                }
            },
            enabled = !viewModel.hasError() && link != null,
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.whatsapp_send_button), style = MaterialTheme.typography.labelLarge)
        }

        if (state.note.isNotEmpty()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall)
        }

        if (showHelpDialog) {
            ModuleHelpDialog(
                title = stringResource(R.string.help_assai_title),
                description = stringResource(R.string.help_assai_desc),
                sections = listOf(
                    HelpSection(
                        title = stringResource(R.string.help_assai_boxes_title),
                        description = stringResource(R.string.help_assai_boxes_desc),
                    ),
                    HelpSection(
                        title = stringResource(R.string.help_assai_weekdays_title),
                        description = stringResource(R.string.help_assai_weekdays_desc),
                    ),
                ),
                onDismissRequest = { showHelpDialog = false },
            )
        }
    }
}
