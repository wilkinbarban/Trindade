package com.trindade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.ui.unit.dp
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.LoginRoute
import com.trindade.app.auth.ProfileRoute
import com.trindade.app.auth.RegisterRoute
import com.trindade.app.auth.RolePolicy
import com.trindade.app.admin.AdminPanelRoute
import com.trindade.app.admin.DriversRoute
import com.trindade.app.admin.TasksRoute
import com.trindade.app.dashboard.DashboardRoute
import com.trindade.app.loading.LoadingEditRoute
import com.trindade.app.loading.LoadingHistoryRoute
import com.trindade.app.loading.LoadingRoute
import com.trindade.app.reports.ReportDetailRoute
import com.trindade.app.reports.ReportEditRoute
import com.trindade.app.reports.ReportGeneratorRoute
import com.trindade.app.reports.ReportsHistoryRoute
import com.trindade.app.ui.theme.TrindadeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** The six top-level surfaces this build can draw. */
    enum class Tab(@StringRes val label: Int) {
        DASHBOARD(R.string.nav_dashboard),
        REPORTS(R.string.nav_reports),
        LOADING(R.string.nav_loading),
        TASKS(R.string.tasks_title),
        DRIVERS(R.string.drivers_title),
        ADMIN(R.string.admin_panel_title),
    }

    companion object {
        /**
         * What this build can draw, in the order the row shows them.
         *
         * What the app *has* is this list; whether a role may see one is `RolePolicy`'s answer, asked in the shell
         * below. The admin surfaces the parity track commits to are missing here because they are not built yet, not
         * because nobody may see them: when one lands it joins this list and the policy decides who gets it.
         */
        val DEFAULT_DESTINATIONS = listOf(
            RolePolicy.EntryPoint.DASHBOARD to Tab.DASHBOARD,
            RolePolicy.EntryPoint.REPORTS to Tab.REPORTS,
            RolePolicy.EntryPoint.LOADING to Tab.LOADING,
            RolePolicy.EntryPoint.CATALOG_TASKS to Tab.TASKS,
            RolePolicy.EntryPoint.CATALOG_DRIVERS to Tab.DRIVERS,
            RolePolicy.EntryPoint.CATEGORIES to Tab.ADMIN,
        )
    }

    private val destinations = DEFAULT_DESTINATIONS

    @Inject
    lateinit var authRepository: AuthRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrindadeTheme {
                AppRoot {
                    // The session is read synchronously rather than observed, because the decision is
                // already available: it is a SharedPreferences read, and awaiting it would mean a
                // frame of the wrong screen for a question that can be answered now. When sign-in
                // gains an asynchronous path this becomes a state flow like any other.
                var signedIn by remember { mutableStateOf(authRepository.hasSession()) }
                // Which report the operator is looking at, if any. Null means the generator.
                var openReportId by remember { mutableStateOf<Int?>(null) }
                // Whether worker registration form is open from login screen.
                var registerOpen by remember { mutableStateOf(false) }
                // Which report is being edited, if any. A destination of its own rather than a mode of
                // the detail's, and it is deliberately not folded into `openReportId` above: the edit
                // screen is reached *from* the detail, so the operator arrives with that flag already
                // set, and the two have to be tellable apart for the branch order below to put the
                // editor on top of the report it is editing rather than replacing what the return trip
                // goes back to.
                var editingReportId by remember { mutableStateOf<Int?>(null) }
                // The loading entry being edited, and the day it was opened from. A destination of its
                // own rather than a mode inside the grid, for the reason the report editor above is: it
                // is reached *from* the grid, so the grid's own flag -- `loadingDay`, or the tab when
                // the grid is today's -- is still set while this is up, and a grid branch that won would
                // make the edit action look like it did nothing at all. Both values are written together
                // where a row offers the way in and cleared together on the way out, so the day is never
                // the absent one while the id is set -- which is what lets the branch below read both.
                //
                // The day is carried rather than read off the grid, because the row is only reachable
                // through it: `GET /api/loading/schedules?date=` is the read that carries a schedule,
                // there is no single-entry route, and this activity has no other place the grid's day is
                // written down -- the tab's grid is today's and is not a value anybody stored.
                var editingScheduleId by remember { mutableStateOf<Int?>(null) }
                var editingScheduleDay by remember { mutableStateOf<String?>(null) }
                // Whether the loading editor has just reported a save. It is the one fact about that
                // write this activity holds and the grid cannot see for itself: the editor is another
                // view model on another copy of the day, so without it the grid underneath is drawn
                // again from the day as it was *before* the edit -- the row the operator just moved
                // comes back in the slot they moved it out of, and their own work looks like it did
                // nothing. The grid's next arrival is the only thing that can act on it, and that
                // arrival takes the fact back (`onStaleRead` below), so one save costs the grid one
                // read and nothing later pays for it again.
                var loadingStale by remember { mutableStateOf(false) }
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
                // Which session this is, as the key the dashboard's view model is built under. It moves when the session
                // changes -- that is what `sessionGeneration` counts, and [AuthRepository] says why a token rotation is
                // not one of the moves -- so the next operator gets a dashboard of their own instead of the previous
                // one's retained summary.
                val sessionKey = remember(signedIn) { authRepository.sessionGeneration.toString() }

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
                    registerOpen = false
                    openReportId = null
                    editingReportId = null
                    editingScheduleId = null
                    editingScheduleDay = null
                    loadingStale = false
                    historyOpen = false
                    loadingHistoryOpen = false
                    loadingDay = null
                    loadingDayFromHistory = false
                    tab = Tab.DASHBOARD
                    signedIn = false
                }

                // The state-clearing callbacks shared between in-screen visible back buttons and
                // Android's native back handling (BackHandler). Centralising them guarantees that system
                // gestures and visible tap targets perform the identical deterministic state unwinding.
                val onBackReportEdit = { editingReportId = null }
                val onBackLoadingEdit = {
                    editingScheduleId = null
                    editingScheduleDay = null
                }
                val onBackReportDetail = { openReportId = null }
                val onBackProfile = { profileOpen = false }
                val onBackReportsHistory = { historyOpen = false }
                val onBackLoadingHistory = { loadingHistoryOpen = false }
                val onBackLoadingDay = {
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
                }
                val onBackLoadingTab = { tab = Tab.DASHBOARD }
                val onBackTasksTab = { tab = Tab.DASHBOARD }
                val onBackDriversTab = { tab = Tab.DASHBOARD }
                val onBackAdminTab = { tab = Tab.DASHBOARD }
                val onBackRegister = { registerOpen = false }

                when {
                    !signedIn && registerOpen -> {
                        BackHandler(onBack = onBackRegister)
                        RegisterRoute(
                            onBackToLogin = onBackRegister,
                        )
                    }
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
                        onNavigateToRegister = { registerOpen = true },
                    )
                    // Ahead of both histories, and that order is the whole of this navigation: each
                    // history opens something over itself, so the open thing has to win while the
                    // history's flag is still true -- otherwise closing the detail would land on the
                    // generator, and the operator would lose the page of the history they were reading.
                    //
                    // The edit surface is ahead of the detail for the same shape of reason and one
                    // degree more specific: it is opened from the detail, so `openReportId` is still set
                    // while it is up, and a detail branch that won would make the edit action look like
                    // it did nothing at all. Closing it drops only `editingReportId`, which is what
                    // returns the operator to the report they were reading -- and that report's route
                    // reads it again on arrival, so the save they just made is what they see.
                    editingReportId != null -> {
                        BackHandler(onBack = onBackReportEdit)
                        ReportEditRoute(
                            reportId = editingReportId!!,
                            onBack = onBackReportEdit,
                            onSaved = onBackReportEdit,
                        )
                    }
                    // The loading editor, beside the report one and ahead of every loading branch for the
                    // same shape of reason: it is opened from the grid, so the flag that draws the grid is
                    // still set underneath it, and either grid branch winning would make the row's edit
                    // action look like it did nothing. Closing it -- by the way out or by the save the route
                    // reports -- drops the id, and that is all leaving it means: the grid underneath was
                    // never replaced, so the operator lands back on the day they left. The day goes with
                    // the id rather than being kept, because it means nothing without the row it was read
                    // for -- and a save leaves one thing behind, `loadingStale` above: the grid's own copy
                    // of that day is now the day as it was before the write.
                    editingScheduleId != null -> {
                        BackHandler(onBack = onBackLoadingEdit)
                        LoadingEditRoute(
                            scheduleId = editingScheduleId!!,
                            date = editingScheduleDay!!,
                            // The session this editor belongs to, for the reason the dashboard's own key is
                            // passed down: the view model lives in the activity's store and must not be
                            // inherited by the next person who signs in.
                            sessionKey = sessionKey,
                            onBack = onBackLoadingEdit,
                            onSaved = {
                                onBackLoadingEdit()
                                // The save, on its way to the grid: whichever door the operator lands back on
                                // draws itself again, reads its day, and takes this back.
                                loadingStale = true
                            },
                        )
                    }
                    openReportId != null -> {
                        BackHandler(onBack = onBackReportDetail)
                        ReportDetailRoute(
                            reportId = openReportId!!,
                            // The report is the one this branch was drawn for: the detail's callback carries
                            // no id of its own (it is `() -> Unit`, and the screen already has the report it
                            // is showing), so the overlay takes the id from the flag that opened it.
                            onEdit = { editingReportId = openReportId },
                            onBack = onBackReportDetail,
                        )
                    }
                    // The account, above the histories for the same reason the report detail is: it is
                    // reached from the tab row and belongs to no tab, so it must not be hidden behind a
                    // history that happens to still be open underneath it. It is below the report detail
                    // because a report the operator opened is the more specific thing on screen.
                    profileOpen -> {
                        BackHandler(onBack = onBackProfile)
                        ProfileRoute(
                            onBack = onBackProfile,
                            // A password change ends the session too, so the same leaving-the-app work is
                            // done for both paths -- and it is done by the panel's own action, after the
                            // sentence explaining why has been read.
                            onSignedOut = { endSession() },
                        )
                    }
                    // Then the reports history, then the loading one: neither is a child of the other,
                    // and each is under the detail it can open.
                    historyOpen -> {
                        BackHandler(onBack = onBackReportsHistory)
                        ReportsHistoryRoute(
                            onBack = onBackReportsHistory,
                            onOpenReport = { openReportId = it },
                        )
                    }
                    loadingHistoryOpen -> {
                        BackHandler(onBack = onBackLoadingHistory)
                        LoadingHistoryRoute(
                            onBack = onBackLoadingHistory,
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
                    }
                    // The grid, when a day was asked for. Above the tab because a requested day is not
                    // the tab's own surface -- it is a screen the history opened, and its own back
                    // action belongs to the day rather than to the tab.
                    loadingDay != null -> {
                        BackHandler(onBack = onBackLoadingDay)
                        LoadingRoute(
                            onBack = onBackLoadingDay,
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
                            onEditEntry = { id, day ->
                                editingScheduleId = id
                                editingScheduleDay = day
                            },
                            // The day the row was opened on travels back with each arrival, and the save
                            // report travels with it: the arrival that reads the day again is the one that
                            // takes the fact.
                            stale = loadingStale,
                            onStaleRead = { loadingStale = false },
                            date = loadingDay!!,
                        )
                    }
                    // And then the persistent top-level app shell for all four destinations.
                    else -> MainAppShell(
                        role = role,
                        currentTab = tab,
                        onSelectTab = { tab = it },
                        onOpenProfile = { profileOpen = true },
                        destinations = destinations,
                    ) {
                        when (tab) {
                            Tab.DASHBOARD -> DashboardRoute(
                                sessionKey = sessionKey,
                                onOpenReport = { openReportId = it },
                                onOpenReports = { tab = Tab.REPORTS },
                                onOpenLoading = { tab = Tab.LOADING },
                            )
                            Tab.REPORTS -> ReportGeneratorRoute(
                                onCreated = { openReportId = it },
                                onOpenHistory = { historyOpen = true },
                            )
                            Tab.LOADING -> {
                                BackHandler(onBack = onBackLoadingTab)
                                LoadingRoute(
                                    onBack = onBackLoadingTab,
                                    onOpenHistory = { loadingHistoryOpen = true },
                                    // The day here is the grid's own -- today -- and it travels to the editor the same
                                    // way a requested day does, because that screen reads the row out of the day it
                                    // was opened on whichever door the operator came through.
                                    onEditEntry = { id, day ->
                                        editingScheduleId = id
                                        editingScheduleDay = day
                                    },
                                    // The save report, for the reason the flag above gives: this door's grid is
                                    // today, which is the one day its arrival would otherwise be answered from
                                    // memory about.
                                    stale = loadingStale,
                                    onStaleRead = { loadingStale = false },
                                )
                            }
                            Tab.TASKS -> {
                                BackHandler(onBack = onBackTasksTab)
                                TasksRoute(
                                    sessionKey = sessionKey,
                                    onBack = onBackTasksTab,
                                )
                            }
                            Tab.DRIVERS -> {
                                BackHandler(onBack = onBackDriversTab)
                                DriversRoute(
                                    sessionKey = sessionKey,
                                    onBack = onBackDriversTab,
                                )
                            }
                            Tab.ADMIN -> {
                                BackHandler(onBack = onBackAdminTab)
                                AdminPanelRoute(
                                    sessionKey = sessionKey,
                                    onBack = onBackAdminTab,
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

/**
 * The top-level root container wrapping all application destinations (both signed-in destinations and login).
 *
 * Edge-to-edge display ([ComponentActivity.enableEdgeToEdge]) renders window contents behind system bars.
 * Applying [safeDrawingPadding] exactly once at this single application root ensures that all screens
 * (including login, profile, report forms, histories, and top-level tab destinations) are inset away
 * from the status bar, display cutout (camera hole punch on devices like Pixel 8), and navigation bar /
 * gesture area, while preventing double-insets on nested screens and avoiding clipping of bottom actions.
 */
@Composable
fun AppRoot(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            content()
        }
    }
}

/**
 * The persistent top-level app shell for signed-in operators.
 *
 * Stateless and Hilt-free so the Compose test lane can render it directly with a synthetic role and
 * verify tab indicator semantics, header actions, and role-based destination filtering without an
 * activity or dependency container.
 *
 * The shell keeps the branded header with the profile action at the top, followed by an active
 * Material3 [PrimaryScrollableTabRow] showing the destinations permitted for [role] by [RolePolicy], and renders
 * [content] in the remaining body space.
 */
@Composable
fun MainAppShell(
    role: String?,
    currentTab: MainActivity.Tab,
    onSelectTab: (MainActivity.Tab) -> Unit,
    onOpenProfile: () -> Unit,
    modifier: Modifier = Modifier,
    destinations: List<Pair<RolePolicy.EntryPoint, MainActivity.Tab>> = MainActivity.DEFAULT_DESTINATIONS,
    content: @Composable () -> Unit,
) {
    val visibleTabs = remember(role, destinations) {
        RolePolicy.visibleDestinations(role, destinations)
    }
    val selectedIndex = if (visibleTabs.isEmpty()) 0 else {
        visibleTabs.indexOf(currentTab).let { if (it >= 0) it else 0 }
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Branded header with the profile action.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.nav_brand_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            TextButton(onClick = onOpenProfile) {
                Text(stringResource(R.string.profile_title))
            }
        }

        // Keep labels readable on phone widths; the row scrolls the selected tab into view.
        if (visibleTabs.isNotEmpty()) {
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedIndex,
                modifier = Modifier.fillMaxWidth(),
                edgePadding = 0.dp,
                minTabWidth = 120.dp,
            ) {
                visibleTabs.forEach { destination ->
                    Tab(
                        selected = currentTab == destination,
                        onClick = { onSelectTab(destination) },
                        text = {
                            Text(
                                text = stringResource(destination.label),
                                maxLines = 1,
                            )
                        },
                    )
                }
            }
        }

        // Content for the active top-level destination.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            content()
        }
    }
}
