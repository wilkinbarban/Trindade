package com.trindade.app.stock

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.ui.BrazilianPhoneVisualTransformation
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.filterPhoneDigits
import com.trindade.app.ui.saoPauloLocalDate
import java.time.LocalDate
import java.util.Locale

/**
 * A past day of counting, as a place of its own.
 *
 * The counter answers what is on the shelf now; this answers what was counted that day, which is a different
 * question asked at a different moment. It is read-only on purpose, and that is the whole shape of the
 * screen: the day is reported by the collective summary the counter draws, with no second calculator beside
 * it. Two calculators for one shelf is how a correction made in the history and a count made in the counter
 * end up disagreeing about the same day.
 */
@Composable
fun StockHistoryScreen(
    onBack: () -> Unit = {},
    viewModel: StockHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val invalidPhoneNote = stringResource(R.string.stock_whatsapp_invalid_phone)
    val openingWhatsappNote = stringResource(R.string.stock_whatsapp_opening)
    val openingEmailNote = stringResource(R.string.stock_email_opening)

    LaunchedEffect(Unit) { viewModel.load() }

    StockHistoryContent(
        state = state,
        onBack = onBack,
        onPickDate = { openDayPicker(context, state.date) { viewModel.onDateChange(it) } },
        onDaySelected = viewModel::onDateChange,
        onNumberChange = { viewModel.onNumberChange(filterPhoneDigits(it)) },
        onWhatsApp = {
            val target = viewModel.whatsappLink()
            if (target == null) {
                viewModel.onNote(invalidPhoneNote)
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
                viewModel.onNote(openingWhatsappNote)
            }
        },
        onEmail = {
            context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse(viewModel.mailtoLink())))
            viewModel.onNote(openingEmailNote)
        },
    )
}

/**
 * The screen without its ViewModel, so what it draws can be asserted without starting Hilt.
 *
 * The rules it carries are the readable ones: the day comes from the collective summary and nothing in this
 * screen edits it, the totals are the server's, and the two ways of sending the day out stay one tap away.
 */
@Composable
internal fun StockHistoryContent(
    state: StockHistoryViewModel.UiState,
    onBack: () -> Unit,
    onPickDate: () -> Unit,
    onDaySelected: (String) -> Unit,
    onNumberChange: (String) -> Unit,
    onWhatsApp: () -> Unit,
    onEmail: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val summary = state.day?.let { stockCollectiveSummary(it) } ?: emptyList()

    Column(
        modifier = modifier
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
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.nav_stock_history), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.stock_history_subtitle_readonly),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The date is picked, not typed: a field that accepts any text is a parser waiting to explain
            // itself, and the calendar below is already bounded to the window the history is for. Tapping the
            // field opens the same calendar as the button, because a field that looks like a field is what an
            // operator taps.
            val interactionSource = remember { MutableInteractionSource() }
            LaunchedEffect(interactionSource) {
                interactionSource.interactions.collect { interaction ->
                    if (interaction is PressInteraction.Release) onPickDate()
                }
            }
            OutlinedTextField(
                value = state.date,
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.stock_count_day)) },
                placeholder = { Text("AAAA-MM-DD") },
                singleLine = true,
                enabled = true,
                interactionSource = interactionSource,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = onPickDate,
                modifier = Modifier.defaultMinSize(minHeight = 48.dp),
            ) {
                Text(stringResource(R.string.stock_pick_date), style = MaterialTheme.typography.labelLarge, maxLines = 1)
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
                        onClick = { onDaySelected(day.date) },
                        enabled = day.date != state.date,
                        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                    ) {
                        Text(
                            text = "${formatDayLabel(day.date)} · ${day.units}",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                        )
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
            Text(
                stringResource(R.string.stock_failed_to_load_count_day),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
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

        // The same card the counter draws, from the same server totals: the history is a reading of the day,
        // and a reading that rendered its own version of the day would be a second opinion instead.
        StockCollectiveSummary(products = summary)

        OutlinedTextField(
            value = state.number,
            onValueChange = onNumberChange,
            visualTransformation = BrazilianPhoneVisualTransformation(),
            label = { Text(stringResource(R.string.whatsapp_phone_label)) },
            placeholder = { Text(stringResource(R.string.whatsapp_phone_placeholder)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Button(
            onClick = onWhatsApp,
            enabled = summary.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.whatsapp_send_button), style = MaterialTheme.typography.labelLarge)
        }

        Button(
            onClick = onEmail,
            enabled = summary.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        ) {
            Text(stringResource(R.string.stock_send_email_button), style = MaterialTheme.typography.labelLarge)
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

/**
 * The calendar, bounded to the last month.
 *
 * `DatePickerDialog` takes its bounds in milliseconds, and it compares them in UTC, so both dates are built
 * at the start of their day in that same zone. Subtracting 30 days from "now" instead would drift by an hour
 * twice a year and occasionally refuse the day it was meant to offer.
 */
private fun openDayPicker(context: Context, selected: String?, onPick: (String) -> Unit) {
    val today = saoPauloLocalDate()
    val initial = selected?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: today

    DatePickerDialog(
        context,
        { _, year, month, day -> onPick(isoDay(year, month + 1, day)) },
        initial.year,
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).apply {
        datePicker.minDate = stockHistoryMinMillis(today)
        datePicker.maxDate = stockHistoryMaxMillis(today)
    }.show()
}

private fun isoDay(year: Int, month: Int, day: Int): String =
    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)
