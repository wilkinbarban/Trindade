package com.trindade.app.stock

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.filterPhoneDigits
import java.time.LocalDate
import java.util.Locale

/**
 * A past day of counting, as a place of its own.
 *
 * The counter answers what is on the shelf now; this answers what was counted that day, which is a different
 * question asked at a different moment. Only what was counted is listed, because a table of twenty-five rows
 * with twenty-four zeroes hides the one row somebody came for.
 *
 * The role arrives as a parameter and defaults to nothing, which is read-only: if nobody told this screen who
 * is looking, it shows the day and offers no correction. The server enforces the same boundary with its 403;
 * this one exists so an operator is not taught about permissions by being refused after trying.
 */
@Composable
fun StockHistoryScreen(
    role: String? = null,
    onBack: () -> Unit = {},
    viewModel: StockHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val counted = viewModel.counted()
    val visibleErrors = viewModel.visibleErrors()
    val canEdit = viewModel.canEdit()

    val invalidPhoneNote = stringResource(R.string.stock_whatsapp_invalid_phone)
    val openingWhatsappNote = stringResource(R.string.stock_whatsapp_opening)
    val openingEmailNote = stringResource(R.string.stock_email_opening)

    LaunchedEffect(role) {
        viewModel.onRoleKnown(role)
        viewModel.load()
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NavigationActionButton(onClick = onBack, text = stringResource(R.string.report_back))
            Column {
                Text(stringResource(R.string.nav_stock_history), style = MaterialTheme.typography.titleLarge)
                Text(
                    if (canEdit) stringResource(R.string.stock_history_subtitle_edit)
                    else stringResource(R.string.stock_history_subtitle_readonly),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.date,
                onValueChange = { viewModel.onDateChange(it) },
                label = { Text(stringResource(R.string.stock_count_day)) },
                placeholder = { Text("AAAA-MM-DD") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { openDayPicker(context, state.date) { viewModel.onDateChange(it) } },
            ) {
                Text(stringResource(R.string.stock_pick_date))
            }
        }

        if (state.days.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.days.forEach { day ->
                    Button(
                        onClick = { viewModel.onDateChange(day.date) },
                        enabled = day.date != state.date,
                    ) {
                        Text("${formatDayLabel(day.date)} · ${day.units}")
                    }
                }
            }
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
            Text(stringResource(R.string.stock_failed_to_load_count_day), color = MaterialTheme.colorScheme.error)
        }

        if (state.day != null && counted.isEmpty()) {
            Text(stringResource(R.string.stock_no_products_counted), style = MaterialTheme.typography.bodyMedium)
        }

        counted.forEach { product ->
            Card {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
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
                    Text(product.category, style = MaterialTheme.typography.bodySmall)
                    Text(
                        stringResource(R.string.stock_boxes_units_format, product.boxes, product.units),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (canEdit) {
                        if (visibleErrors.contains(product.code)) {
                            Text(
                                stringResource(R.string.stock_invalid_boxes_error),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = state.quantities[product.code] ?: "",
                                onValueChange = { viewModel.onQuantityChange(product.code, it) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .width(110.dp)
                                    .onFocusChanged { focus ->
                                        if (focus.isFocused) viewModel.onFocus(product.code) else viewModel.onBlur(product.code)
                                    },
                            )
                            Button(
                                onClick = { viewModel.addEntry(product) },
                                enabled = !addDisabled(state.quantities[product.code] ?: ""),
                            ) {
                                Text(stringResource(R.string.stock_add_button))
                            }
                            Button(onClick = { viewModel.resetProduct(product) }) {
                                Text(stringResource(R.string.stock_reset_button))
                            }
                        }
                    }
                }
            }
        }

        HorizontalDivider()
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.stock_day_total))
            Text(
                stringResource(R.string.stock_day_total_format, counted.sumOf { it.boxes }, counted.sumOf { it.units }),
                style = MaterialTheme.typography.titleMedium,
            )
        }

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

        Button(
            onClick = {
                val target = viewModel.whatsappLink()
                if (target == null) {
                    viewModel.onNote(invalidPhoneNote)
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                    viewModel.onNote(openingWhatsappNote)
                }
            },
            enabled = counted.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.whatsapp_send_button))
        }

        Button(
            onClick = {
                // El destino es el buzon de la planta; el correo del autor no se usa aca.
                context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(viewModel.mailtoLink())))
                viewModel.onNote(openingEmailNote)
            },
            enabled = counted.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.stock_send_email_button))
        }

        if (state.note.isNotEmpty()) {
            Text(state.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun formatDayLabel(isoDate: String): String {
    val parts = isoDate.split("-")
    return if (parts.size == 3) "${parts[2]}/${parts[1]}" else isoDate
}

private fun openDayPicker(context: Context, selected: String?, onPick: (String) -> Unit) {
    val initial = selected?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()

    DatePickerDialog(
        context,
        { _, year, month, day -> onPick(isoDay(year, month + 1, day)) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

private fun isoDay(year: Int, month: Int, day: Int): String =
    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
