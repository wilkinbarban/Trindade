package com.trindade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.LoginRoute
import com.trindade.app.auth.ProfileRoute
import com.trindade.app.auth.RolePolicy
import com.trindade.app.dashboard.DashboardRoute
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

    /** The three top-level surfaces this build can draw. Nothing else is a destination yet. */
    private enum class Tab(@StringRes val label: Int) {
        DASHBOARD(R.string.nav_dashboard),
        REPORTS(R.string.nav_reports),
        LOADING(R.string.nav_loading),
    }

    /**
     * What this build can draw, in the order the row shows them.
     *
     * What the app *has* is this list; whether a role may see one is `RolePolicy`'s answer, asked in the row
     * below. The admin surfaces the parity track commits to are missing here because they are not built yet, not
     * because nobody may see them: when one lands it joins this list and the policy decides who gets it.
     */
    private val destinations = listOf(
        RolePolicy.EntryPoint.DASHBOARD to Tab.DASHBOARD,
        RolePolicy.EntryPoint.REPORTS to Tab.REPORTS,
        RolePolicy.EntryPoint.LOADING to Tab.LOADING,
    )

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
                // Whether the profile is showing instead of the tabs. The account is not one of them: see
                // the tab row below.
                var profileOpen by remember { mutableStateOf(false) }
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
                // Which of the three top-level surfaces is showing. The dashboard is what the app opens on,
                // and it is the web's own answer to the same question: a signed-in operator lands on
                // `/dashboard` there, both from its `*` route and from its post-login redirect. The start is
                // a literal because the dashboard is visible to every role -- `RolePolicy` has it in the set
                // both roles share -- so there is no session for which this names a surface the row would not
                // offer; the day an administrator-only entry sits first in that row, the start has to be
                // derived from the policy rather than written here.
                var tab by remember { mutableStateOf(Tab.DASHBOARD) }
                // The role this session was opened with. Read once per session rather than observed, for the reason
                // `hasSession()` above gives: it is a SharedPreferences read, and the answer cannot change while the session
                // is the same one -- the server hands the role over at sign-in and a token rotation carries none. Keyed on
                // `signedIn`, which is exactly when a new role can arrive.
                val role = remember(signedIn) { authRepository.sessionRole() }

                /**
                 * Leaves the app: the session is over, so nothing may be left armed for the next one.
                 *
                 * Every destination this activity keeps is dropped here, not just the one the operator
                 * was on. They are remembered across the login screen -- the composition that holds them
                 * stays alive above it -- so an open report, either history, a requested day or the
                 * profile itself would be re-entered by the next person who signs in, landing them on a
                 * screen about somebody else's session. The tab goes back to where a shift starts for the
                 * same reason.
                 */
                fun endSession() {
                    profileOpen = false
                    openReportId = null
                    historyOpen = false
                    loadingHistoryOpen = false
                    loadingDay = null
                    loadingDayFromHistory = false
                    tab = Tab.DASHBOARD
                    signedIn = false
                }

                when {
                    !signedIn -> LoginRoute(
                        // Nothing is checked here on purpose. This used to re-read `hasSession()` before
                        // entering, because login's view model is scoped to the activity and its flag
                        // never went back to false: the login screen re-enters the composition on a
                        // sign-out, its effect fired again on that stale flag, and honouring it would
                        // have put the operator back inside the app with no session at all. That was the
                        // symptom of a sticky flag, and the sticky flag is what has been fixed -- the
                        // screen consumes the event now, so one sign-in is reported exactly once and this
                        // callback runs for it and for nothing else. The gate is gone rather than kept
                        // beside the fix because two mechanisms for one thing hide each other: while it
                        // stood, the swallowed second sign-in read as the operator's mistake instead of
                        // as a bug, and a guard whose false branch is reachable only by that bug is a
                        // hiding place rather than an invariant. `signedIn` still starts from
                        // `hasSession()` above, which is the one place the store genuinely decides.
                        onSignedIn = { signedIn = true },
                    )
                    // Ahead of both histories, and that order is the whole of this navigation: each
                    // history opens something over itself, so the open thing has to win while the
                    // history's flag is still true -- otherwise closing the detail would land on the
                    // generator, and the operator would lose the page of the history they were reading.
                    openReportId != null -> ReportDetailRoute(
                        reportId = openReportId!!,
                        onBack = { openReportId = null },
                    )
                    // The account, above the histories for the same reason the report detail is: it is
                    // reached from the tab row and belongs to no tab, so it must not be hidden behind a
                    // history that happens to still be open underneath it. It is below the report detail
                    // because a report the operator opened is the more specific thing on screen.
                    profileOpen -> ProfileRoute(
                        onBack = { profileOpen = false },
                        // A password change ends the session too, so the same leaving-the-app work is
                        // done for both paths -- and it is done by the panel's own action, after the
                        // sentence explaining why has been read.
                        onSignedOut = { endSession() },
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
                                tab = Tab.DASHBOARD
                            }
                        },
                        onOpenHistory = {
                            // The day goes here as well, and for the reason the comment above gives: this is
                            // the other door into the history, and a day kept behind it is what made the two
                            // screens bounce -- the history's back returned to a grid still bearing the day,
                            // and that grid's own back opened the history again. Null means the day's screen
                            // is over: what the history returns to is the tab's grid, today, which is also
                            // what the grid is asked for again when the tab branch renders below.
                            loadingDay = null
                            loadingDayFromHistory = false
                            loadingHistoryOpen = true
                        },
                        date = loadingDay!!,
                    )
                    // And then the loading tab, the one that is drawn as a screen of its own. It is a day
                    // of its own -- today -- so it is reached without a day above it.
                    tab == Tab.LOADING -> LoadingRoute(
                        onBack = { tab = Tab.DASHBOARD },
                        onOpenHistory = { loadingHistoryOpen = true },
                    )
                    else -> Column {
                        // One row of tabs, and the account at the far end of it. The row draws whatever the
                        // policy says this role may open -- today the dashboard, the reports and the schedule --
                        // and the profile is not one of them: it is the operator's own account rather than a
                        // surface the app is about, so it is set apart instead of joining them. A navigation
                        // library would be more than this app has places to go, and the back action each
                        // screen already owns is the whole of the routing it needs.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // The row is drawn from the policy, not from `destinations` alone: the list says what
                            // the app has and the policy says what this role may open, and this is the one place
                            // the two meet.
                            RolePolicy.visibleDestinations(role, destinations).forEach { destination ->
                                TextButton(onClick = { tab = destination }) { Text(stringResource(destination.label)) }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { profileOpen = true }) { Text(stringResource(R.string.profile_title)) }
                        }
                        // What the row switches between. The row is drawn for both, which is where these two differ from the
                        // loading tab: that one is a screen with a back action of its own, while the dashboard and the report
                        // generator are what the row is for -- and the web draws its navigation on the dashboard too.
                        when (tab) {
                            Tab.DASHBOARD -> DashboardRoute(
                                onOpenReport = { openReportId = it },
                                onOpenReports = { tab = Tab.REPORTS },
                                onOpenLoading = { tab = Tab.LOADING },
                            )
                            else -> ReportGeneratorRoute(
                                onCreated = { openReportId = it },
                                onOpenHistory = { historyOpen = true },
                            )
                        }
                    }
                }
            }
        }
    }
}
