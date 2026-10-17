package com.trindade.app.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.trindade.app.R
import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.loading.saoPauloToday

/**
 * The dashboard: the five cards the web's own dashboard draws, in the web's order and with the web's
 * words, presented in a structured Material 3 hierarchy with brand-aligned palette.
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
    onOpenLoadingDay: (String) -> Unit,
    // Los tres accesos directos a las calculadoras: las pestañas ya existen, así que la tarjeta
    // solo tiene que llevar a ellas.
    onOpenStock: () -> Unit,
    onOpenStockHistory: () -> Unit,
    onOpenAssai: () -> Unit,
    onShareActiveSchedule: () -> Unit,
    modifier: Modifier = Modifier,
    // Los tres accesos directos: la pantalla los ofrece y la ruta los entrega.
) {
    when {
        state.loading -> Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                color = MaterialTheme.colorScheme.primary,
            )
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
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.dashboard_unreachable),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        else -> {
            // Read once, here, rather than at each of the uses below.
            val summary: DashboardSummary = state.summary

            Column(
                modifier = modifier
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(R.string.dashboard_overview),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
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
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = stringResource(R.string.dashboard_higiene_count, summary.higieneDone),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(R.string.dashboard_recepcion_count, summary.recepcionDone),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                // Las mismas dos cosas que ofrece la tarjeta de la web, y con la misma regla de la web:
                // el día que abre es el del LOTE, que es el único que nombra filas en la grilla. La fecha
                // que se muestra arriba es el día de carga y es otro día -- el lote del viernes se carga
                // el lunes --, así que abrir esa fecha dejaba al operador en una pantalla vacía. Cuando no
                // hay lote no se finge una puerta: se invita a crear el cronograma de hoy.
                val activeScheduleBatchDate = summary.activeScheduleBatchDate
                val canViewActiveSchedule = summary.activeScheduleCount > 0 && activeScheduleBatchDate != null
                val openActiveSchedule = {
                    if (canViewActiveSchedule) onOpenLoadingDay(activeScheduleBatchDate!!) else onOpenLoading()
                }
                DashboardCard(
                    label = stringResource(R.string.dashboard_active_schedule),
                    onClick = openActiveSchedule,
                ) {
                    SummaryValue(summary.activeScheduleCount)
                    // The day the count belongs to, under it: a number of carregamentos without its day is
                    // one the operator cannot act on, which is what this card stops being once it is here.
                    Text(
                        text = summary.activeScheduleDate,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = openActiveSchedule) {
                        Text(
                            text = stringResource(
                                if (canViewActiveSchedule) R.string.dashboard_view_schedule
                                else R.string.dashboard_create_schedule
                            ),
                        )
                    }
                    // La otra mitad de la paridad: la web ofrece mandar el cronograma por WhatsApp desde esta
                    // misma tarjeta. Sin fecha de lote no hay texto que mandar, así que el botón se apaga en
                    // vez de abrir un compartidor vacío.
                    TextButton(
                        onClick = onShareActiveSchedule,
                        enabled = activeScheduleBatchDate != null,
                    ) {
                        Text(text = stringResource(R.string.dashboard_share_schedule))
                    }
                }

                // La tarjeta lleva a la misma pestaña que su texto nombra: el acceso directo y el menú
                // dicen lo mismo porque usan la misma cadena.
                DashboardCard(
                    label = stringResource(R.string.nav_stock_counter),
                    onClick = onOpenStock,
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_stock_counter),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // La tarjeta lleva a la misma pestaña que su texto nombra: el acceso directo y el menú
                // dicen lo mismo porque usan la misma cadena.
                DashboardCard(
                    label = stringResource(R.string.nav_stock_history),
                    onClick = onOpenStockHistory,
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_stock_history),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                // La tarjeta lleva a la misma pestaña que su texto nombra: el acceso directo y el menú
                // dicen lo mismo porque usan la misma cadena.
                DashboardCard(
                    label = stringResource(R.string.nav_assai),
                    onClick = onOpenAssai,
                ) {
                    Text(
                        text = stringResource(R.string.dashboard_assai),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
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
            // El credito es el ultimo hijo del Column scrolleable y no parte de la rama de arriba: como
            // hijo de un bloque condicional solo se dibujaba en esa rama, y ahi no era el pie de nada.
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
    onOpenLoadingDay: (String) -> Unit,
    // Los tres accesos directos: la pantalla los ofrece y la ruta los entrega.
    onOpenStock: () -> Unit,
    onOpenStockHistory: () -> Unit,
    onOpenAssai: () -> Unit,
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
        onOpenLoadingDay = onOpenLoadingDay,
        onOpenStock = onOpenStock,
        onOpenStockHistory = onOpenStockHistory,
        onOpenAssai = onOpenAssai,
        onShareActiveSchedule = viewModel::shareActiveSchedule,
    )
}

/**
 * One card of the dashboard: a label, the value below it, and a click when the web gives the card one.
 *
 * Clickable shortcut cards use [OutlinedCard] with primary-tinted labels and explicit boundary to
 * make actionable destinations visibly evident. Static metric cards use [ElevatedCard] on warm neutral
 * surface to cleanly distinguish informational counts from shortcuts.
 *
 * Both card variants span full width and enforce a minimum height of 72dp to easily exceed the
 * 48dp touch target accessibility requirement.
 */
@Composable
private fun DashboardCard(
    label: String,
    onClick: (() -> Unit)?,
    value: @Composable () -> Unit,
) {
    val isInteractive = onClick != null
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = if (isInteractive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(8.dp))
            value()
        }
    }

    if (onClick != null) {
        OutlinedCard(
            onClick = onClick,
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp),
            colors = CardDefaults.outlinedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            content()
        }
    } else {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 72.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
        ) {
            content()
        }
    }
}

/** One of the four plain counts, in the style those four share. The two breakdown lines of the first card
 * are drawn smaller -- see the card -- because at headline size they wrap on a narrow phone. */
@Composable
private fun SummaryValue(count: Int) {
    Text(
        text = count.toString(),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
        fontWeight = FontWeight.Bold,
    )
}
