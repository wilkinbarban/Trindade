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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.trindade.app.admin.AdminPanelRoute
import com.trindade.app.admin.DriversRoute
import com.trindade.app.admin.TasksRoute
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.LoginRoute
import com.trindade.app.auth.ProfileRoute
import com.trindade.app.auth.RegisterRoute
import com.trindade.app.auth.RolePolicy
import com.trindade.app.dashboard.DashboardRoute
import com.trindade.app.loading.LoadingEditRoute
import com.trindade.app.loading.LoadingHistoryRoute
import com.trindade.app.loading.LoadingRoute
import com.trindade.app.reports.ReportDetailRoute
import com.trindade.app.reports.ReportEditRoute
import com.trindade.app.reports.ReportGeneratorRoute
import com.trindade.app.reports.ReportsHistoryRoute
import com.trindade.app.ui.components.NavigationActionButton
import com.trindade.app.ui.theme.TrindadeTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
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
         * below. The admin surfaces are integrated here and the role policy decides who gets them.
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
                    var signedIn by remember { mutableStateOf(authRepository.hasSession()) }
                    var openReportId by remember { mutableStateOf<Int?>(null) }
                    var registerOpen by remember { mutableStateOf(false) }
                    var editingReportId by remember { mutableStateOf<Int?>(null) }
                    var editingScheduleId by remember { mutableStateOf<Int?>(null) }
                    var editingScheduleDay by remember { mutableStateOf<String?>(null) }
                    var loadingStale by remember { mutableStateOf(false) }
                    var profileOpen by remember { mutableStateOf(false) }
                    var historyOpen by remember { mutableStateOf(false) }
                    var loadingHistoryOpen by remember { mutableStateOf(false) }
                    var loadingDay by remember { mutableStateOf<String?>(null) }
                    var loadingDayFromHistory by remember { mutableStateOf(false) }
                    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val role = remember(signedIn) { authRepository.sessionRole() }
                    val sessionKey = remember(signedIn) { authRepository.sessionGeneration.toString() }

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
                            onSignedIn = { signedIn = true },
                            onNavigateToRegister = { registerOpen = true },
                        )
                        editingReportId != null -> {
                            BackHandler(onBack = onBackReportEdit)
                            ReportEditRoute(
                                reportId = editingReportId!!,
                                onBack = onBackReportEdit,
                                onSaved = onBackReportEdit,
                            )
                        }
                        editingScheduleId != null -> {
                            BackHandler(onBack = onBackLoadingEdit)
                            LoadingEditRoute(
                                scheduleId = editingScheduleId!!,
                                date = editingScheduleDay!!,
                                sessionKey = sessionKey,
                                onBack = onBackLoadingEdit,
                                onSaved = {
                                    onBackLoadingEdit()
                                    loadingStale = true
                                },
                            )
                        }
                        openReportId != null -> {
                            BackHandler(onBack = onBackReportDetail)
                            ReportDetailRoute(
                                reportId = openReportId!!,
                                onEdit = { editingReportId = openReportId },
                                onBack = onBackReportDetail,
                            )
                        }
                        profileOpen -> {
                            BackHandler(onBack = onBackProfile)
                            ProfileRoute(
                                onBack = onBackProfile,
                                onSignedOut = { endSession() },
                            )
                        }
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
                                    loadingHistoryOpen = false
                                    loadingDay = date
                                    loadingDayFromHistory = true
                                    tab = Tab.LOADING
                                },
                            )
                        }
                        loadingDay != null -> {
                            BackHandler(onBack = onBackLoadingDay)
                            LoadingRoute(
                                onBack = onBackLoadingDay,
                                onOpenHistory = {
                                    loadingDay = null
                                    loadingDayFromHistory = false
                                    loadingHistoryOpen = true
                                },
                                onEditEntry = { id, day ->
                                    editingScheduleId = id
                                    editingScheduleDay = day
                                },
                                stale = loadingStale,
                                onStaleRead = { loadingStale = false },
                                date = loadingDay!!,
                            )
                        }
                        else -> MainAppShell(
                            role = role,
                            currentTab = tab,
                            onSelectTab = { tab = it },
                            onOpenProfile = { profileOpen = true },
                            destinations = destinations,
                            drawerState = drawerState,
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
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackLoadingTab)
                                    LoadingRoute(
                                        onBack = onBackLoadingTab,
                                        onOpenHistory = { loadingHistoryOpen = true },
                                        onEditEntry = { id, day ->
                                            editingScheduleId = id
                                            editingScheduleDay = day
                                        },
                                        stale = loadingStale,
                                        onStaleRead = { loadingStale = false },
                                    )
                                }
                                Tab.TASKS -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackTasksTab)
                                    TasksRoute(
                                        sessionKey = sessionKey,
                                        onBack = onBackTasksTab,
                                    )
                                }
                                Tab.DRIVERS -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackDriversTab)
                                    DriversRoute(
                                        sessionKey = sessionKey,
                                        onBack = onBackDriversTab,
                                    )
                                }
                                Tab.ADMIN -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackAdminTab)
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
    drawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
    content: @Composable () -> Unit,
) {
    val visibleTabs = remember(role, destinations) {
        RolePolicy.visibleDestinations(role, destinations)
    }
    val selectedIndex = if (visibleTabs.isEmpty()) 0 else {
        visibleTabs.indexOf(currentTab).let { if (it >= 0) it else 0 }
    }
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier.widthIn(min = 320.dp, max = 320.dp),
            ) {
                if (drawerState.isOpen || drawerState.isAnimationRunning) {
                    Column(
                        modifier = Modifier
                            .testTag("navigation_drawer_sheet")
                            .verticalScroll(rememberScrollState())
                            .padding(vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.nav_brand_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                        visibleTabs.forEach { destination ->
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        text = stringResource(destination.label),
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                },
                                selected = currentTab == destination,
                                onClick = {
                                    onSelectTab(destination)
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier
                                    .padding(NavigationDrawerItemDefaults.ItemPadding)
                                    .defaultMinSize(minHeight = 48.dp),
                            )
                        }
                    }
                }
            }
        },
        modifier = modifier,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        NavigationActionButton(
                            onClick = { scope.launch { drawerState.open() } },
                            text = stringResource(R.string.nav_menu),
                        )
                        Text(
                            text = stringResource(R.string.nav_brand_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 8.dp),
                        )
                        NavigationActionButton(
                            onClick = onOpenProfile,
                            text = stringResource(R.string.profile_title),
                        )
                    }

                    if (visibleTabs.isNotEmpty()) {
                        PrimaryScrollableTabRow(
                            selectedTabIndex = selectedIndex,
                            modifier = Modifier.fillMaxWidth(),
                            edgePadding = 0.dp,
                            minTabWidth = 120.dp,
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                            visibleTabs.forEach { destination ->
                                Tab(
                                    selected = currentTab == destination,
                                    onClick = { onSelectTab(destination) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    text = {
                                        Text(
                                            text = stringResource(destination.label),
                                            maxLines = 1,
                                            style = if (currentTab == destination) {
                                                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                            } else {
                                                MaterialTheme.typography.labelLarge
                                            },
                                            color = if (currentTab == destination) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                content()
            }
        }
    }

    BackHandler(enabled = drawerState.isOpen) {
        scope.launch { drawerState.close() }
    }
}
