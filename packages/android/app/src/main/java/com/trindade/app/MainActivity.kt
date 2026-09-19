package com.trindade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.LoginRoute
import com.trindade.app.loading.LoadingHistoryRoute
import com.trindade.app.loading.LoadingRoute
import com.trindade.app.reports.ReportDetailRoute
import com.trindade.app.reports.ReportGeneratorRoute
import com.trindade.app.reports.ReportsHistoryRoute
import com.trindade.app.ui.theme.TrindadeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** The two top-level surfaces. Nothing else is a destination yet. */
    private enum class Tab { REPORTS, LOADING }

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrindadeTheme {
                // The session is read synchronously rather than observed, because the decision is
                // already available: it is a SharedPreferences read, and awaiting it would mean a
                // frame of the wrong screen for a question that can be answered now. When sign-in
                // gains an asynchronous path this becomes a state flow like any other.
                var signedIn by remember { mutableStateOf(authRepository.hasSession()) }
                // Which report the operator is looking at, if any. Null means the generator.
                var openReportId by remember { mutableStateOf<Int?>(null) }
                // Whether the report history is showing instead of the generator.
                var historyOpen by remember { mutableStateOf(false) }
                // Whether the loading history is showing instead of the grid.
                var loadingHistoryOpen by remember { mutableStateOf(false) }
                // The day the grid was opened on, or null while it shows today.
                var loadingDay by remember { mutableStateOf<String?>(null) }
                // Whether that day came from a row of the loading history. It is the whole of what the
                // grid's back action needs to know, and there is no way to derive it: the grid's own
                // surface is reached without a day as well, from the loading tab, and back from there
                // belongs to the reports tab like every other top-level screen. Today a day only ever
                // arrives from the history, because the grid has no picker -- D4 recorded that as a
                // product change rather than a gap -- but the two doors are what the order below has to
                // tell apart, so the origin is carried explicitly rather than assumed.
                var loadingDayFromHistory by remember { mutableStateOf(false) }
                // Which of the two top-level surfaces is showing. Reports is where a shift starts.
                var tab by remember { mutableStateOf(Tab.REPORTS) }

                when {
                    !signedIn -> LoginRoute(onSignedIn = { signedIn = true })
                    // Ahead of both histories, and that order is the whole of this navigation: each
                    // history opens something over itself, so the open thing has to win while the
                    // history's flag is still true -- otherwise closing the detail would land on the
                    // generator, and the operator would lose the page of the history they were reading.
                    openReportId != null -> ReportDetailRoute(
                        reportId = openReportId!!,
                        onBack = { openReportId = null },
                    )
                    // Then the reports history, then the loading one: neither is a child of the other,
                    // and each is under the detail it can open.
                    historyOpen -> ReportsHistoryRoute(
                        onBack = { historyOpen = false },
                        onOpenReport = { openReportId = it },
                    )
                    loadingHistoryOpen -> LoadingHistoryRoute(
                        onBack = { loadingHistoryOpen = false },
                        onOpenDay = { date ->
                            // The history closes on the way in, because the grid branch below is read
                            // only when this one is false, and the day carries the fact that it came
                            // from here. The tab is set as well, so that whatever the operator does
                            // next -- the grid's back, or the grid's own way into the history -- lands
                            // in the loading surface rather than in the reports one.
                            loadingHistoryOpen = false
                            loadingDay = date
                            loadingDayFromHistory = true
                            tab = Tab.LOADING
                        },
                    )
                    // The grid, when a day was asked for. Above the tab because a requested day is not
                    // the tab's own surface -- it is a screen the history opened, and its own back
                    // action belongs to the day rather than to the tab.
                    loadingDay != null -> LoadingRoute(
                        onBack = {
                            // The day is dropped on the way out. That is what stops the two screens
                            // from bouncing: this screen's back returns to the history, leaving it
                            // returns to the grid, and a day kept here would make those two actions
                            // alternate forever.
                            loadingDay = null
                            if (loadingDayFromHistory) {
                                loadingDayFromHistory = false
                                loadingHistoryOpen = true
                            } else {
                                tab = Tab.REPORTS
                            }
                        },
                        onOpenHistory = { loadingHistoryOpen = true },
                        date = loadingDay!!,
                    )
                    // And then the two tabs. The loading tab is a day of its own -- today -- so it is
                    // reached without a day above it.
                    tab == Tab.LOADING -> LoadingRoute(
                        onBack = { tab = Tab.REPORTS },
                        onOpenHistory = { loadingHistoryOpen = true },
                    )
                    else -> Column {
                        // Two surfaces, one row of tabs. A navigation library would be more than this
                        // app has places to go, and the back action each screen already owns is the
                        // whole of the routing it needs.
                        Row {
                            TextButton(onClick = { tab = Tab.REPORTS }) { Text(stringResource(R.string.nav_reports)) }
                            TextButton(onClick = { tab = Tab.LOADING }) { Text(stringResource(R.string.nav_loading)) }
                        }
                        ReportGeneratorRoute(
                            onCreated = { openReportId = it },
                            onOpenHistory = { historyOpen = true },
                        )
                    }
                }
            }
        }
    }
}
