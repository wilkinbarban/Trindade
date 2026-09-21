package com.trindade.app.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.DashboardSummary

/**
 * The dashboard: the five cards the web's own dashboard draws, in the web's order and with the web's
 * words.
 *
 * Stateless, so the Compose test lane can render it with a state built by hand and no ViewModel; the
 * same split the login screen makes and for the same reason.
 *
 * Two things the web's page has are deliberately **not** drawn here, and a reader comparing the two
 * clients has to be told which, so that a divergence is not mistaken for a gap:
 *
 *  * The emoji the web puts on each card (`📋`, `🚛`, `👥`, `📊`, `📂`) are decoration. Five glyphs are not
 *    worth a new icon dependency, and nothing on the screen is reached or identified by them.
 *  * `reportsToday` -- which the contract carries and the endpoint computes -- is not drawn at all,
 *    because the web's own card titled "Relatórios Hoje" never shows that count: it shows the
 *    higiene/recepção breakdown instead, and no screen on either client reads `reportsToday`.
 *
 * `DashboardSummary`'s two remaining fields, `higieneTotal` and `recepcionTotal`, are passed to the same
 * two strings the web passes them to; those strings (the web's own `pt-BR` text, kept here character for
 * character) name only the `done` half, so the totals reach no pixel on either client. They are named
 * here rather than dropped quietly: whether the web's two lines should carry the totals is a copy
 * decision, made once, in the web's locale file, and this screen follows it rather than inventing a
 * second reading of the same sentence.
 *
 * The screens the cards open are named in [DashboardRoute]'s comment: the web links the first two cards
 * and draws the other three as plain numbers.
 */
@Composable
fun DashboardScreen(
    state: DashboardViewModel.UiState,
    onOpenReport: (Int) -> Unit,
    onOpenReports: () -> Unit,
    onOpenLoading: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        state.loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator()
        }

        // No retry *control*, and none is drawn here. The web draws this same failure the same way -- an
        // alert with no action inside it -- and its page refetches on every visit; this screen's recovery
        // is the same one: leaving the tab and coming back. That works because the arrival, not the view
        // model's construction, is what reads: `DashboardRoute` refreshes on every arrival above. The
        // sentence names no cause, for the reason the view model's KDoc gives.
        state.failed -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.dashboard_unreachable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        else -> {
            // Read non-null once, here, instead of `state.summary!!` at each of the six uses below: the
            // branch above is what guarantees it, and a reader should not have to hold two branches in
            // their head to know that a `!!` cannot throw.
            val summary: DashboardSummary = state.summary ?: return

            Column(
                modifier = modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_overview),
                    style = MaterialTheme.typography.titleLarge,
                )

                // The two values of the first card, drawn as the web draws them: one line per module
                // rather than the card's own big number, and at `bodyMedium` rather than the
                // `headlineSmall` the four plain counts use -- the web draws them at `text-xs`/`text-sm`,
                // and at headline size they wrap on a narrow phone. The click is the web's own rule as
                // well -- the card opens the latest report when there is one and the reports list
                // otherwise -- which is why it cannot be decided here: only the state knows whether
                // `latestReportId` is null.
                DashboardCard(
                    label = stringResource(R.string.dashboard_reports_today),
                    onClick = {
                        val latestReportId = summary.latestReportId
                        if (latestReportId != null) onOpenReport(latestReportId) else onOpenReports()
                    },
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_higiene_count, summary.higieneDone),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(R.string.dashboard_recepcion_count, summary.recepcionDone),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                DashboardCard(
                    label = stringResource(R.string.dashboard_schedules_tomorrow),
                    onClick = onOpenLoading,
                ) {
                    SummaryValue(summary.schedulesTomorrow)
                }

                // The three cards the web leaves unlinked, and the reason is the same one there: they
                // are counts the operator reads and not doors to anywhere.
                DashboardCard(label = stringResource(R.string.dashboard_active_users), onClick = null) {
                    SummaryValue(summary.activeUsers)
                }
                DashboardCard(label = stringResource(R.string.dashboard_reports_total), onClick = null) {
                    SummaryValue(summary.reportsTotal)
                }
                DashboardCard(label = stringResource(R.string.dashboard_schedules_total), onClick = null) {
                    SummaryValue(summary.schedulesTotal)
                }

                Text(
                    text = stringResource(R.string.dashboard_deferred_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The screen with its ViewModel attached and the three ways out of it handed over.
 *
 * The two callbacks that leave the dashboard are the web's own two links, and that is the only reason
 * they exist: the reports card leads to `/reports/{latestReportId}` -- or to the reports list at
 * `/reports` when there is no latest report -- and the schedules card leads to `/loading`. The other
 * three cards are counts, so they lead nowhere and have no callback.
 */
@Composable
fun DashboardRoute(
    onOpenReport: (Int) -> Unit,
    onOpenReports: () -> Unit,
    onOpenLoading: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    // Every arrival reads again: the summary is about today, the operator can come back to it at any point in
    // the shift, and the arrival is also this screen's only recovery path when a read failed. It is the web
    // page's own shape -- that one fetches on mount.
    LaunchedEffect(Unit) { viewModel.refresh() }
    DashboardScreen(
        state = state,
        onOpenReport = onOpenReport,
        onOpenReports = onOpenReports,
        onOpenLoading = onOpenLoading,
    )
}

/**
 * One card of the dashboard: a label, the value below it, and a click when the web gives the card one.
 *
 * The two clickable cards use Material's own `Card(onClick = ...)` overload rather than a `clickable`
 * modifier inside a plain card: the overload is where the ripple, the interaction source and the
 * button-like semantics come from, and a card that only looks pressable is the failure that overload
 * exists to remove.
 *
 * The label and the value are one merged node on the clickable cards and two on the others, because
 * that is what `clickable` does to semantics. Nothing here reads the tree, so the difference is only
 * worth knowing when a test selects by the label: on a clickable card the label selects the card.
 */
@Composable
private fun DashboardCard(
    label: String,
    onClick: (() -> Unit)?,
    value: @Composable () -> Unit,
) {
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            value()
        }
    }

    if (onClick != null) {
        Card(onClick = onClick) { content() }
    } else {
        Card { content() }
    }
}

/** One of the four plain counts, in the style those four share. The two breakdown lines of the first card
 * are drawn smaller -- see the card -- because at headline size they wrap on a narrow phone. */
@Composable
private fun SummaryValue(count: Int) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.headlineSmall,
    )
}
