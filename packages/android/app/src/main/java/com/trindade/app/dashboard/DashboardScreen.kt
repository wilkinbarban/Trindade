package com.trindade.app.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.DashboardSummary

/**
 * The dashboard: the five cards matching and exceeding the web's own dashboard hierarchy, in the web's
 * order, with the web's copy, domain badge glyphs (`📋`, `🚛`, `👥`, `📊`, `📂`), semantic color tokens,
 * and explicit navigation affordances on actionable cards.
 *
 * Stateless, so the Compose test lane can render it with a state built by hand and no ViewModel; the
 * same split the login screen makes and for the same reason.
 *
 * `reportsToday` -- which the contract carries and the endpoint computes -- is not drawn at all,
 * because the web's own card titled "Relatórios Hoje" never shows that count: it shows the
 * higiene/recepção breakdown instead, and no screen on either client reads `reportsToday`.
 *
 * `DashboardSummary`'s two remaining fields, `higieneTotal` and `recepcionTotal`, are **not read here at
 * all**. The app's two strings interpolate one count each and it is the `done` half, so the totals reach no
 * pixel on this screen. The web's own two lines interpolate both -- it passes `done` and `total` to the
 * same `pt-BR` text kept here character for character -- and its copy renders only the `done` count as
 * well, which is why the totals are named here rather than dropped quietly: whether the web's two lines
 * should carry them is a copy decision, made once, in the web's locale file, and this screen follows it
 * rather than inventing a second reading of the same sentence.
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

        // This branch *is* the failure state: not loading and nothing to show means the read did not arrive.
        // That is why there is no flag to consult here -- a flag is a second thing saying what the state
        // already says, and two things can disagree, which is the disagreement this screen cannot have.
        //
        // No retry *control*, and none is drawn here. The web draws this same failure the same way -- an
        // alert with no action inside it -- and its page refetches on every visit; this screen's recovery
        // is the same one: leaving the tab and coming back. That works because the arrival, not the view
        // model's construction, is what reads: `DashboardRoute` refreshes on every arrival above. The
        // sentence names no cause, for the reason the view model's KDoc gives.
        state.summary == null -> Box(
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
            // Read once, here, rather than at each of the uses below.
            val summary: DashboardSummary = state.summary

            Column(
                modifier = modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_overview),
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                )

                // The two values of the first card, drawn as the web draws them: one line per module
                // rather than the card's own big number, with semantic badge styling. The click is
                // the web's own rule as well -- the card opens the latest report when there is one and
                // the reports list otherwise -- which is why it cannot be decided here: only the state
                // knows whether `latestReportId` is null.
                DashboardCard(
                    label = stringResource(R.string.dashboard_reports_today),
                    domainIcon = DashboardIcons.REPORTS_TODAY,
                    domainColor = DashboardColors.ReportsToday,
                    onClick = {
                        val latestReportId = summary.latestReportId
                        if (latestReportId != null) onOpenReport(latestReportId) else onOpenReports()
                    },
                ) {
                    BreakdownMetricBadge(
                        text = stringResource(R.string.dashboard_higiene_count, summary.higieneDone),
                        accentColor = DashboardColors.ReportsToday,
                    )
                    Spacer(Modifier.height(4.dp))
                    BreakdownMetricBadge(
                        text = stringResource(R.string.dashboard_recepcion_count, summary.recepcionDone),
                        accentColor = DashboardColors.ReportsToday,
                    )
                }

                DashboardCard(
                    label = stringResource(R.string.dashboard_schedules_tomorrow),
                    domainIcon = DashboardIcons.SCHEDULES_TOMORROW,
                    domainColor = DashboardColors.SchedulesTomorrow,
                    onClick = onOpenLoading,
                ) {
                    SummaryMetricValue(
                        count = summary.schedulesTomorrow,
                        color = DashboardColors.SchedulesTomorrow,
                    )
                }

                // The three cards the web leaves unlinked, and the reason is the same one there: they
                // are counts the operator reads and not doors to anywhere.
                DashboardCard(
                    label = stringResource(R.string.dashboard_active_users),
                    domainIcon = DashboardIcons.ACTIVE_USERS,
                    domainColor = DashboardColors.ActiveUsers,
                    onClick = null,
                ) {
                    SummaryMetricValue(
                        count = summary.activeUsers,
                        color = DashboardColors.ActiveUsers,
                    )
                }
                DashboardCard(
                    label = stringResource(R.string.dashboard_reports_total),
                    domainIcon = DashboardIcons.REPORTS_TOTAL,
                    domainColor = DashboardColors.ReportsTotal,
                    onClick = null,
                ) {
                    SummaryMetricValue(
                        count = summary.reportsTotal,
                        color = DashboardColors.ReportsTotal,
                    )
                }
                DashboardCard(
                    label = stringResource(R.string.dashboard_schedules_total),
                    domainIcon = DashboardIcons.SCHEDULES_TOTAL,
                    domainColor = DashboardColors.SchedulesTotal,
                    onClick = null,
                ) {
                    SummaryMetricValue(
                        count = summary.schedulesTotal,
                        color = DashboardColors.SchedulesTotal,
                    )
                }

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_deferred_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
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
    // The key is the session this dashboard is for, and it is what keeps a retained state off this
    // screen. The view model lives in the activity's store, so without a key a dashboard built for a
    // previous session would be the very instance this screen draws -- and its retained summary would be
    // composed before the effect below ever ran, because the first composition happens before any
    // `LaunchedEffect` does. Keyed to the session, that instance is not this screen's: the one it draws is
    // new, and a new one starts at `loading = true`, so the first frame is the spinner.
    sessionKey: String,
    onOpenReport: (Int) -> Unit,
    onOpenReports: () -> Unit,
    onOpenLoading: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(key = sessionKey),
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
 * One prominent metric card of the dashboard:
 *  - Title and metric value / breakdown on the left
 *  - Domain badge with domain icon glyph on the right
 *  - Explicit visual navigation affordance ("→") on actionable cards
 *  - Semantic Role.Button and click semantics on clickable cards; non-clickable cards remain informational.
 */
@Composable
private fun DashboardCard(
    label: String,
    domainIcon: String,
    domainColor: Color,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    value: @Composable () -> Unit,
) {
    val isClickable = onClick != null

    val cardContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                value()
            }
            Spacer(Modifier.width(16.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                DomainBadge(
                    icon = domainIcon,
                    color = domainColor,
                )
                if (isClickable) {
                    ClickableAffordance(
                        color = domainColor,
                    )
                }
            }
        }
    }

    if (isClickable) {
        Card(
            onClick = onClick!!,
            modifier = modifier
                .fillMaxWidth()
                .semantics { role = Role.Button },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
            border = BorderStroke(1.dp, domainColor.copy(alpha = 0.35f)),
            elevation = CardDefaults.cardElevation(
                defaultElevation = 2.dp,
                pressedElevation = 4.dp,
            ),
        ) {
            cardContent()
        }
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        ) {
            cardContent()
        }
    }
}

/**
 * Domain badge container holding the domain glyph in a tinted rounded box.
 */
@Composable
private fun DomainBadge(
    icon: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .background(
                color = color.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = icon,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp),
        )
    }
}

/**
 * Explicit visual affordance indicator ("→") on actionable cards.
 */
@Composable
private fun ClickableAffordance(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(28.dp)
            .background(
                color = color.copy(alpha = 0.15f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "→",
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
        )
    }
}

/**
 * Prominent bold metric count rendered in the domain's semantic color.
 */
@Composable
private fun SummaryMetricValue(
    count: Int,
    color: Color,
) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.Bold),
        color = color,
    )
}

/**
 * Breakdown metric line rendered inside a styled badge with domain color accent.
 */
@Composable
private fun BreakdownMetricBadge(
    text: String,
    accentColor: Color,
) {
    Surface(
        color = accentColor.copy(alpha = 0.08f),
        shape = RoundedCornerShape(6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private object DashboardColors {
    // Web: text-blue-600 (#2563eb)
    val ReportsToday = Color(0xFF2563EB)
    // Web: text-green-600 (#16a34a)
    val SchedulesTomorrow = Color(0xFF16A34A)
    // Web: text-purple-600 (#9333ea)
    val ActiveUsers = Color(0xFF9333EA)
    // Web: text-indigo-600 (#4f46e5)
    val ReportsTotal = Color(0xFF4F46E5)
    // Web: text-teal-600 (#0d9488)
    val SchedulesTotal = Color(0xFF0D9488)
}

private object DashboardIcons {
    const val REPORTS_TODAY = "📋"
    const val SCHEDULES_TOMORROW = "🚛"
    const val ACTIVE_USERS = "👥"
    const val REPORTS_TOTAL = "📊"
    const val SCHEDULES_TOTAL = "📂"
}
