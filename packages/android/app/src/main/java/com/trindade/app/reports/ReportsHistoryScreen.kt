package com.trindade.app.reports

import android.app.DatePickerDialog
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.ReportsResponseReportsInner
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * The report history: one page of reports, filtered by a day or a month.
 *
 * Stateless like the other screens, with one exception: whether the month dialog is open is screen
 * state by nature -- nothing above it acts on that, and the choice it produces is handed straight to
 * the view model as an ISO string.
 *
 * The day filter is the platform's own `DatePickerDialog`, and the month filter is a list of the last
 * twelve months rather than the same picker. That distinction is deliberate: the platform picker
 * answers a day, so using it to choose a month would mean asking for a value and then ignoring it, and
 * a control whose answer has no effect is a control that lies about what it does.
 */
@Composable
fun ReportsHistoryScreen(
    state: ReportsHistoryViewModel.UiState,
    onBack: () -> Unit,
    onOpenReport: (Int) -> Unit,
    onDateSelected: (String) -> Unit,
    onMonthSelected: (String) -> Unit,
    onClearFilters: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDeactivate: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pickingMonth by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            TextButton(onClick = onBack) { Text(stringResource(R.string.report_back)) }
        }

        item {
            Column {
                Text(text = stringResource(R.string.history_title), style = MaterialTheme.typography.titleLarge)
                Text(
                    // The active filter in words. A day reads as the operators write dates and a month
                    // by name, because `2026-09` is the wire's spelling and not a label.
                    text = when {
                        state.date != null -> stringResource(R.string.history_filtered, displayDate(state.date))
                        state.month != null -> stringResource(R.string.history_filtered, displayMonth(state.month))
                        else -> stringResource(R.string.history_all_reports)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { openDayPicker(context, state.date, onDateSelected) }) {
                    Text(
                        text = state.date
                            ?.let { stringResource(R.string.history_filter_day_value, displayDate(it)) }
                            ?: stringResource(R.string.history_filter_day),
                    )
                }
                OutlinedButton(onClick = { pickingMonth = true }) {
                    Text(
                        text = state.month
                            ?.let { stringResource(R.string.history_filter_month_value, displayMonth(it)) }
                            ?: stringResource(R.string.history_filter_month),
                    )
                }
                // Offered only when there is a filter to clear: a control that does nothing when tapped
                // is worse than one that is not there, because the operator cannot tell which it is.
                if (state.date != null || state.month != null) {
                    TextButton(onClick = onClearFilters) { Text(stringResource(R.string.history_clear_filters)) }
                }
            }
        }

        state.message?.let { message ->
            item {
                Text(
                    text = message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        if (state.loading) {
            item { CircularProgressIndicator() }
        }

        // Only when a read answered. An empty list next to an error sentence would state a failed read
        // as a fact about the history, and "no reports for this period" is a different sentence from
        // "the server did not answer".
        if (state.loaded && state.items.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(state.items, key = { it.id }) { report ->
            ReportRow(
                report = report,
                busy = state.busy,
                onOpen = { onOpenReport(report.id) },
                onDeactivate = { onDeactivate(report.id) },
                onDelete = { onDelete(report.id) },
            )
        }

        // The footer is a claim about a page that exists, so it waits for a read to answer. On a failed
        // first read there is no page: the counts are the state's defaults, and "Página 1 de 1
        // (0 registros)" printed under an unreachable message would be the same mistake the sentence
        // above goes out of its way to avoid, one line further down.
        if (state.loaded) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(
                            R.string.history_pagination,
                            state.page,
                            // The server answers `totalPages` 0 for an empty history, and the SPA draws
                            // the same maximum for the same reason: "Página 1 de 0" is a sentence about
                            // the arithmetic rather than about the reports.
                            maxOf(1, state.totalPages),
                            state.total,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row {
                        TextButton(
                            onClick = onPrevious,
                            // Disabled while a page is in flight as well as at the bound, so a second
                            // tap cannot ask for a page relative to an answer that has not arrived.
                            enabled = state.canGoPrevious && !state.loading,
                        ) {
                            Text(stringResource(R.string.history_previous))
                        }
                        TextButton(
                            onClick = onNext,
                            enabled = state.canGoNext && !state.loading,
                        ) {
                            Text(stringResource(R.string.history_next))
                        }
                    }
                }
            }
        }
    }

    if (pickingMonth) {
        // Computed once per opening rather than on every recomposition, and dropped when the dialog
        // leaves the composition -- which is what makes reopening it pick up a month that has since
        // changed.
        val months = remember { recentMonths(LocalDate.now()) }

        AlertDialog(
            onDismissRequest = { pickingMonth = false },
            title = { Text(stringResource(R.string.history_month_picker_title)) },
            text = {
                // Scrollable, because twelve rows do not fit the dialog's slot on a short screen and an
                // unscrollable list would hide the older half of the year behind an invisible edge.
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    months.forEach { iso ->
                        TextButton(
                            onClick = {
                                pickingMonth = false
                                onMonthSelected(iso)
                            },
                        ) {
                            Text(displayMonth(iso))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { pickingMonth = false }) { Text(stringResource(R.string.loading_cancel)) }
            },
        )
    }
}

/**
 * One report in the list: its date, its shift, its author, and what may be done with it.
 *
 * The block that opens the detail is one tap target and the two lifecycle actions sit outside it, so a
 * tap on "Excluir" is never also a tap on "open this report".
 */
@Composable
private fun ReportRow(
    report: ReportsResponseReportsInner,
    busy: Boolean,
    onOpen: () -> Unit,
    onDeactivate: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !busy, onClick = onOpen)
                .padding(vertical = 4.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = displayDate(report.reportDate), style = MaterialTheme.typography.bodyLarge)
                Text(text = turnoLabel(report.turno), style = MaterialTheme.typography.bodyMedium)
                // `== false`, and not `!= true`: the badge is a claim that the report is inactive, and
                // an absent flag is not a claim about anything. The five lifecycle flags are optional in
                // the contract, so null is a state this screen has to survive without inventing a fact.
                if (report.isActive == false) {
                    Text(
                        text = stringResource(R.string.history_inactive),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            val notes = notesPreview(report.notes)
            Text(
                text = if (notes == null) report.user.displayName else "${report.user.displayName} — $notes",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        if (offersDeactivate(report) || offersDelete(report)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // Each action is drawn only where the server said it would be accepted, which is the
                // `== true` inside those two predicates rather than a role decided here: this client does
                // not know the caller's role, and the flag is the server's own answer to that question.
                if (offersDeactivate(report)) {
                    TextButton(onClick = onDeactivate, enabled = !busy) {
                        Text(stringResource(R.string.history_deactivate))
                    }
                }
                if (offersDelete(report)) {
                    TextButton(onClick = onDelete, enabled = !busy) {
                        Text(stringResource(R.string.history_delete))
                    }
                }
            }
        }

        HorizontalDivider()
    }
}

/**
 * Opens the platform's day picker, which is the OS control for a date and brings the platform's own
 * layout, locale and accessibility with it. See the screen's own note for why the month filter does not
 * use it.
 *
 * It opens on the day already chosen, so re-picking is a correction rather than a restart, and on today
 * when there is none.
 */
private fun openDayPicker(context: Context, selected: String?, onPick: (String) -> Unit) {
    val initial = selected?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()

    DatePickerDialog(
        context,
        { _, year, month, day -> onPick(isoDay(year, month + 1, day)) },
        initial.year,
        // The dialog counts months from zero and the server's ISO string counts from one.
        initial.monthValue - 1,
        initial.dayOfMonth,
    ).show()
}

/**
 * The ISO `YYYY-MM-DD` the server validates, assembled rather than formatted through the device's
 * locale: an Arabic-locale phone would otherwise hand the picker's digits out in a numeral system the
 * server's `^\d{4}-\d{2}-\d{2}$` refuses with a 400.
 */
private fun isoDay(year: Int, month: Int, day: Int): String =
    String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day)

/**
 * The server's `YYYY-MM-DD` as the `dd/mm/yyyy` the operators read.
 *
 * Reordered rather than formatted, which is exactly what the SPA's own `formatDate` does, and for the
 * same reason: a locale-formatted date changes shape with the phone's settings, and a date is a field
 * here rather than a sentence. A value that is not three parts comes back as it arrived, because this
 * is a display and a malformed date is a contract defect worth seeing rather than crashing over.
 */
private fun displayDate(iso: String): String {
    val parts = iso.split('-')
    return if (parts.size == 3) "${parts[2]}/${parts[1]}/${parts[0]}" else iso
}

/**
 * The ISO `YYYY-MM` as a month in words, e.g. `Setembro de 2026`.
 *
 * Portuguese by name rather than by the device's locale, because the app's copy is Portuguese and a
 * phone set to Spanish would otherwise label the months in a language the rest of the screen is not in.
 */
private fun displayMonth(iso: String): String {
    val month = runCatching { YearMonth.parse(iso) }.getOrNull() ?: return iso
    val name = month.month
        .getDisplayName(TextStyle.FULL, PORTUGUESE)
        .replaceFirstChar { it.uppercase() }
    return "$name de ${month.year}"
}

/** The one locale this screen formats in, which is the language of its own copy. */
private val PORTUGUESE: Locale = Locale.forLanguageTag("pt-BR")

/** The shift as a word, from the generated enum: a third shift would fail to compile here instead. */
@Composable
private fun turnoLabel(turno: ReportsResponseReportsInner.Turno): String = when (turno) {
    // No emoji: the SPA decorates the same word with a sunrise and a moon, and that is the web page's
    // decoration rather than something this list has to carry.
    ReportsResponseReportsInner.Turno.tarde -> stringResource(R.string.history_turno_tarde)
    ReportsResponseReportsInner.Turno.noite -> stringResource(R.string.history_turno_noite)
}

/**
 * The last [count] months as ISO `YYYY-MM`, newest first, ending at [today]'s own month.
 *
 * Takes the date as an argument rather than reading a clock, which is both what makes it testable and
 * what keeps it off the device's timezone: month arithmetic on a `LocalDate` has no zone to consult, so
 * a phone set to the wrong one still offers the same twelve months.
 */
internal fun recentMonths(today: LocalDate, count: Int = 12): List<String> =
    (0 until count).map { YearMonth.from(today).minusMonths(it.toLong()).toString() }

/**
 * The note as one line of a list row, or null when there is nothing to show.
 *
 * Cut at [limit] characters with an ellipsis after them, so the operator can see there is more rather
 * than reading a sentence that stops mid-word. The limit counts the note's own characters and the
 * ellipsis is added after them, which is the same 60 the SPA slices at.
 *
 * A blank note is no note. The SPA's truthiness test would draw a separator with nothing after it for a
 * note that is only spaces, and an empty line under the author's name reads as a rendering fault rather
 * than as an unremarkable absence; so a whitespace-only note collapses to null, the same as an absent
 * one. The note is trimmed, because padding the server stored would otherwise push the author and the
 * note apart on a narrow row.
 */
internal fun notesPreview(notes: String?, limit: Int = 60): String? {
    val text = notes?.trim().orEmpty()
    if (text.isEmpty()) return null
    return if (text.length <= limit) text else text.take(limit) + "…"
}

/** The screen with its ViewModel attached. */
@Composable
fun ReportsHistoryRoute(
    onBack: () -> Unit,
    onOpenReport: (Int) -> Unit,
    viewModel: ReportsHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    ReportsHistoryScreen(
        state = state,
        onBack = onBack,
        onOpenReport = onOpenReport,
        onDateSelected = viewModel::onDateSelected,
        onMonthSelected = viewModel::onMonthSelected,
        onClearFilters = viewModel::clearFilters,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onDeactivate = viewModel::deactivate,
        onDelete = viewModel::delete,
    )
}
