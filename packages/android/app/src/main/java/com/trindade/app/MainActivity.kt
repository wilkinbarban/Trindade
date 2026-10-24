package com.trindade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.trindade.app.network.GuestReadonlyNotice
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.painterResource
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.hilt.navigation.compose.hiltViewModel
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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
import com.trindade.app.ui.theme.TrindadeTheme
import com.trindade.app.assai.AssaiCalculatorScreen
import com.trindade.app.share.ShareAppScreen
import com.trindade.app.stock.StockCalculatorScreen
import com.trindade.app.stock.StockHistoryScreen
import com.trindade.app.ui.components.DeveloperCredit
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * The surfaces this build can draw.
     *
     * A tab is a screen, not a place in the menu: the histories and the two catalog surfaces live inside
     * the tab that owns them, so several of these are reached from a drawer entry or a module action rather
     * than from the tab row. [TAB_DESTINATIONS] is what the row shows and [DRAWER_ENTRIES] is what the
     * drawer lists; neither is the whole enum.
     */
    enum class Tab(@StringRes val label: Int) {
        DASHBOARD(R.string.nav_dashboard),
        REPORTS(R.string.nav_reports),
        LOADING(R.string.nav_loading),
        MASSAS_LAUNCH(R.string.massas_title),
        ADMIN(R.string.admin_panel_title),
        ASSAI(R.string.nav_assai),
        STOCK_COUNTER(R.string.nav_stock_counter),
        STOCK_HISTORY(R.string.nav_stock_history),
        SHARE(R.string.nav_share),
    }

    /**
     * The screens that live *inside* a tab rather than beside it.
     *
     * The three histories of massas, reports and loading are the same tab's surface in another state, so a
     * drawer entry that opens one says so with an overlay instead of pretending the history is a tab of its
     * own -- which is exactly how the row filled up with destinations nobody navigates to from a row.
     */
    enum class Overlay {
        MASSAS_HISTORY,
        REPORTS_HISTORY,
        LOADING_HISTORY,
        ADMIN_AUDIT,
    }

    /** One tab, and the overlay the visit opens on top of it. */
    data class NavTarget(val tab: Tab, val overlay: Overlay? = null)

    /** A labelled, icon-bearing navigation destination and the [NavTarget] it leads to. */
    data class Destination(
        val entryPoint: RolePolicy.EntryPoint,
        @StringRes val label: Int,
        @DrawableRes val icon: Int,
        val target: NavTarget,
    )

    /** A drawer row: either a destination or the heading of the group under it. */
    sealed interface DrawerEntry {
        data class Link(val destination: Destination) : DrawerEntry

        data class Section(@StringRes val label: Int) : DrawerEntry
    }

    companion object {
        /**
         * The tab row, in the order it shows them.
         *
         * What the app *has* is this list; whether a role may see one is `RolePolicy`'s answer, asked in the shell
         * below. The histories and the two catalog surfaces are deliberately absent: they hang off the module
         * that owns them, so a row of eight stays readable on a phone.
         */
        val TAB_DESTINATIONS = listOf(
            Destination(RolePolicy.EntryPoint.DASHBOARD, R.string.nav_dashboard, R.drawable.ic_dashboard, NavTarget(Tab.DASHBOARD)),
            Destination(RolePolicy.EntryPoint.REPORTS, R.string.nav_reports, R.drawable.ic_reports, NavTarget(Tab.REPORTS)),
            Destination(RolePolicy.EntryPoint.LOADING, R.string.nav_loading, R.drawable.ic_truck, NavTarget(Tab.LOADING)),
            Destination(RolePolicy.EntryPoint.MASSAS_LAUNCH, R.string.massas_title, R.drawable.ic_layers, NavTarget(Tab.MASSAS_LAUNCH)),
            Destination(RolePolicy.EntryPoint.ASSAI_ORDER, R.string.nav_assai, R.drawable.ic_calculator, NavTarget(Tab.ASSAI)),
            Destination(RolePolicy.EntryPoint.STOCK_COUNTER, R.string.nav_stock_counter, R.drawable.ic_boxes, NavTarget(Tab.STOCK_COUNTER)),
            Destination(RolePolicy.EntryPoint.ADMIN_PANEL, R.string.admin_panel_title, R.drawable.ic_admin, NavTarget(Tab.ADMIN)),
            Destination(RolePolicy.EntryPoint.SHARE_APP, R.string.nav_share, R.drawable.ic_share, NavTarget(Tab.SHARE)),
        )

        /**
         * The drawer, in the web application's order and with its icons.
         *
         * The drawer is the directory and the row is the shortcut bar: this is where every surface appears,
         * including the four histories the row no longer carries. The role filter runs over the links, and a
         * section heading whose links all filtered out disappears with them -- which is how a worker gets
         * `Painel Admin` without an empty `Administração` group above nothing.
         */
        val DRAWER_ENTRIES = listOf(
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.DASHBOARD, R.string.nav_dashboard, R.drawable.ic_dashboard, NavTarget(Tab.DASHBOARD))),
            DrawerEntry.Section(R.string.nav_section_operations),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.REPORTS, R.string.nav_reports, R.drawable.ic_reports, NavTarget(Tab.REPORTS))),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.LOADING, R.string.nav_loading, R.drawable.ic_truck, NavTarget(Tab.LOADING))),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.MASSAS_LAUNCH, R.string.massas_title, R.drawable.ic_layers, NavTarget(Tab.MASSAS_LAUNCH))),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.ASSAI_ORDER, R.string.nav_assai, R.drawable.ic_calculator, NavTarget(Tab.ASSAI))),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.STOCK_COUNTER, R.string.nav_stock_counter, R.drawable.ic_boxes, NavTarget(Tab.STOCK_COUNTER))),
            DrawerEntry.Link(
                Destination(
                    RolePolicy.EntryPoint.MASSAS_HISTORY,
                    R.string.massas_history_title,
                    R.drawable.ic_calendar,
                    NavTarget(Tab.MASSAS_LAUNCH, Overlay.MASSAS_HISTORY),
                ),
            ),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.STOCK_HISTORY, R.string.nav_stock_history, R.drawable.ic_archive, NavTarget(Tab.STOCK_HISTORY))),
            DrawerEntry.Link(
                Destination(
                    RolePolicy.EntryPoint.REPORTS,
                    R.string.history_title,
                    R.drawable.ic_file_clock,
                    NavTarget(Tab.REPORTS, Overlay.REPORTS_HISTORY),
                ),
            ),
            DrawerEntry.Link(
                Destination(
                    RolePolicy.EntryPoint.LOADING,
                    R.string.loading_history_title,
                    R.drawable.ic_history,
                    NavTarget(Tab.LOADING, Overlay.LOADING_HISTORY),
                ),
            ),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.SHARE_APP, R.string.nav_share, R.drawable.ic_share, NavTarget(Tab.SHARE))),
            DrawerEntry.Section(R.string.nav_section_administration),
            DrawerEntry.Link(Destination(RolePolicy.EntryPoint.ADMIN_PANEL, R.string.admin_panel_title, R.drawable.ic_admin, NavTarget(Tab.ADMIN))),
            DrawerEntry.Link(
                Destination(
                    RolePolicy.EntryPoint.AUDIT,
                    R.string.admin_section_audit,
                    R.drawable.ic_audit,
                    NavTarget(Tab.ADMIN, Overlay.ADMIN_AUDIT),
                ),
            ),
        )
    }

    @Inject
    lateinit var authRepository: AuthRepository

    @Inject
    lateinit var updateRepository: com.trindade.app.update.UpdateRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TrindadeTheme {
                AppRoot {
                    var signedIn by remember { mutableStateOf(authRepository.hasSession()) }
                    var updateAvailable by remember { mutableStateOf(false) }
                    var showMandatoryUpdateDialog by remember { mutableStateOf(false) }
                    var openReportId by remember { mutableStateOf<Int?>(null) }
                    var registerOpen by remember { mutableStateOf(false) }
                    var editingReportId by remember { mutableStateOf<Int?>(null) }
                    var editingScheduleId by remember { mutableStateOf<Int?>(null) }
                    var editingScheduleDay by remember { mutableStateOf<String?>(null) }
                    var loadingStale by remember { mutableStateOf(false) }
                    var profileOpen by remember { mutableStateOf(false) }
                    var historyOpen by remember { mutableStateOf(false) }
                    var loadingHistoryOpen by remember { mutableStateOf(false) }
                    var massasHistoryOpen by remember { mutableStateOf(false) }
                    var massasLaunchDay by remember { mutableStateOf<String?>(null) }
                    var massasDayFromHistory by remember { mutableStateOf(false) }
                    var loadingDay by remember { mutableStateOf<String?>(null) }
                    var loadingDayFromHistory by remember { mutableStateOf(false) }
                    var tab by remember { mutableStateOf(Tab.DASHBOARD) }
                    // La sección del panel que el cajón pidió abrir (Auditoria llega así). Vive acá y no en
                    // AdminPanelRoute porque el pedido nace fuera del panel: el cajón lo deja hecho y el
                    // panel lo consume una sola vez.
                    var adminRequestedSection by remember { mutableStateOf<com.trindade.app.admin.AdminSection?>(null) }
                    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                    val role = remember(signedIn) { authRepository.sessionRole() }
                    val sessionKey = remember(signedIn) { authRepository.sessionGeneration.toString() }
                    val sessionScope = rememberCoroutineScope()
                    val lifecycleOwner = LocalLifecycleOwner.current

                    val checkAppUpdate: suspend () -> Unit = {
                        if (signedIn) {
                            val release = updateRepository.latestRelease()
                            if (release != null) {
                                val availability = com.trindade.app.update.UpdateDetector.checkUpdate(
                                    release = release,
                                    currentVersionName = com.trindade.app.BuildConfig.APP_VERSION_NAME,
                                    currentBuildTimeMillis = com.trindade.app.BuildConfig.BUILD_TIME,
                                    installedSha256 = com.trindade.app.update.InstalledAppInfo.getInstalledApkSha256(this@MainActivity),
                                    installedSizeBytes = com.trindade.app.update.InstalledAppInfo.getInstalledApkSize(this@MainActivity),
                                )
                                updateAvailable = availability.isUpdateAvailable
                                if (availability.isUpdateAvailable) {
                                    showMandatoryUpdateDialog = true
                                }
                            }
                        }
                    }

                    LaunchedEffect(signedIn) {
                        checkAppUpdate()
                    }

                    DisposableEffect(lifecycleOwner, signedIn) {
                        val observer = LifecycleEventObserver { _, event ->
                            if (event == Lifecycle.Event.ON_RESUME && signedIn) {
                                sessionScope.launch {
                                    checkAppUpdate()
                                }
                            }
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }
                    // Para cerrar la sesión de verdad al salir al login: limpiar sólo el estado de la
                    // pantalla dejaría el token vivo en el dispositivo.

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
                        massasHistoryOpen = false
                        massasLaunchDay = null
                        massasDayFromHistory = false
                        loadingDay = null
                        loadingDayFromHistory = false
                        adminRequestedSection = null
                        tab = Tab.DASHBOARD
                        signedIn = false
                        updateAvailable = false
                        showMandatoryUpdateDialog = false
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
                    val onBackAssaiTab = { tab = Tab.DASHBOARD }
                    val onBackShareTab = { tab = Tab.DASHBOARD }
                    val onBackMassasLaunchTab = {
                        if (massasDayFromHistory) {
                            massasDayFromHistory = false
                            massasLaunchDay = null
                            massasHistoryOpen = true
                        } else {
                            tab = Tab.DASHBOARD
                        }
                    }
                    val onBackReportsTab = { tab = Tab.DASHBOARD }
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
                        updateAvailable -> {
                            BackHandler(enabled = true) {
                                showMandatoryUpdateDialog = true
                            }
                            ProfileRoute(
                                onBack = { showMandatoryUpdateDialog = true },
                                onSignedOut = { endSession() },
                                isMandatoryUpdate = true,
                            )
                        }
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
                        massasHistoryOpen -> {
                            val onBackMassasHistory = { massasHistoryOpen = false }
                            BackHandler(onBack = onBackMassasHistory)
                            val historyViewModel: com.trindade.app.massas.MassasHistoryViewModel = hiltViewModel()
                            com.trindade.app.massas.MassasHistoryScreen(
                                viewModel = historyViewModel,
                                onBack = onBackMassasHistory,
                                onSelectDate = { date ->
                                    massasHistoryOpen = false
                                    massasLaunchDay = date
                                    val isToday = date == com.trindade.app.massas.MassasLaunchViewModel.saopauloToday()
                                    massasDayFromHistory = !isToday
                                    tab = Tab.MASSAS_LAUNCH
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
                            currentOverlay = when {
                                massasHistoryOpen -> Overlay.MASSAS_HISTORY
                                historyOpen -> Overlay.REPORTS_HISTORY
                                loadingHistoryOpen -> Overlay.LOADING_HISTORY
                                else -> null
                            },
                            onSelectTab = { tab = it },
                            onOpenProfile = { profileOpen = true },
                            onGoToLogin = {
                                // El aviso manda al login y la sesión se cierra de verdad: endSession limpia
                                // el estado de la pantalla y el repositorio borra el token del dispositivo, que
                                // es lo que el botón promete.
                                endSession()
                                sessionScope.launch { authRepository.logout() }
                            },
                            updateAvailable = updateAvailable,
                            tabDestinations = TAB_DESTINATIONS,
                            drawerEntries = DRAWER_ENTRIES,
                            onSelectDrawerEntry = { entry ->
                                val target = entry.target
                                // Salir de toda superposición antes de pintar el destino nuevo: un histórico
                                // que sobreviviera a su visita taparía la pantalla que se acaba de pedir.
                                massasHistoryOpen = false
                                historyOpen = false
                                loadingHistoryOpen = false
                                adminRequestedSection = null
                                if (target.tab != Tab.LOADING) {
                                    loadingDay = null
                                    loadingDayFromHistory = false
                                }
                                if (target.tab != Tab.MASSAS_LAUNCH) {
                                    massasLaunchDay = null
                                    massasDayFromHistory = false
                                }
                                when (target.overlay) {
                                    Overlay.MASSAS_HISTORY -> massasHistoryOpen = true
                                    Overlay.REPORTS_HISTORY -> historyOpen = true
                                    Overlay.LOADING_HISTORY -> loadingHistoryOpen = true
                                    Overlay.ADMIN_AUDIT -> adminRequestedSection =
                                        com.trindade.app.admin.AdminSection.AUDIT
                                    null -> Unit
                                }
                                tab = target.tab
                            },
                            drawerState = drawerState,
                        ) {
                            when (tab) {
                                Tab.ASSAI -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackAssaiTab)
                                    AssaiCalculatorScreen()
                                }
                                Tab.SHARE -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackShareTab)
                                    ShareAppScreen()
                                }
                                Tab.MASSAS_LAUNCH -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackMassasLaunchTab)
                                    val massasViewModel: com.trindade.app.massas.MassasLaunchViewModel = hiltViewModel()
                                    LaunchedEffect(massasLaunchDay) {
                                        if (massasLaunchDay != null) {
                                            massasViewModel.loadDay(massasLaunchDay!!)
                                        } else {
                                            massasViewModel.loadDay(com.trindade.app.massas.MassasLaunchViewModel.saopauloToday())
                                        }
                                    }
                                    com.trindade.app.massas.MassasLaunchScreen(
                                        viewModel = massasViewModel,
                                        onNavigateToHistory = { massasHistoryOpen = true },
                                        onBackToHistory = if (massasDayFromHistory) onBackMassasLaunchTab else null,
                                    )
                                }
                                // El histórico propaga el rol resuelto de sesión para que el
                                // Administrador pueda realizar correcciones sobre días anteriores.
                                Tab.STOCK_HISTORY -> {
                                    val onBackStockHistory = { tab = Tab.STOCK_COUNTER }
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackStockHistory)
                                    StockHistoryScreen(
                                        role = role,
                                        onBack = onBackStockHistory,
                                    )
                                }
                                Tab.STOCK_COUNTER -> {
                                    BackHandler(enabled = !drawerState.isOpen) { tab = Tab.DASHBOARD }
                                    StockCalculatorScreen(
                                        role = role,
                                        onOpenHistory = { tab = Tab.STOCK_HISTORY },
                                    )
                                }
                                Tab.DASHBOARD -> DashboardRoute(
                                    sessionKey = sessionKey,
                                    onOpenReport = { openReportId = it },
                                    onOpenReports = { tab = Tab.REPORTS },
                                    onOpenLoading = { tab = Tab.LOADING },
                                    // A day that is not today's is opened as the day it is, which is the
                                    // same screen the loading history uses for the same purpose.
                                    onOpenLoadingDay = { day ->
                                        loadingDay = day
                                        loadingDayFromHistory = false
                                        tab = Tab.LOADING
                                    },
                                    // Las tres calculadoras ya son pestañas: el acceso directo solo cambia de
                                    // pestaña, igual que el menú.
                                    onOpenStock = { tab = Tab.STOCK_COUNTER },
                                    onOpenStockHistory = { tab = Tab.STOCK_HISTORY },
                                    onOpenAssai = { tab = Tab.ASSAI },
                                    onOpenMassas = {
                                        tab = Tab.MASSAS_LAUNCH
                                        massasHistoryOpen = false
                                        massasLaunchDay = null
                                        massasDayFromHistory = false
                                    },
                                    onOpenMassasHistory = {
                                        tab = Tab.MASSAS_LAUNCH
                                        massasHistoryOpen = true
                                        massasLaunchDay = null
                                        massasDayFromHistory = false
                                    },
                                )
                                Tab.REPORTS -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackReportsTab)
                                    ReportGeneratorRoute(
                                        onCreated = { openReportId = it },
                                        onOpenHistory = { historyOpen = true },
                                    )
                                }
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
                                Tab.ADMIN -> {
                                    BackHandler(enabled = !drawerState.isOpen, onBack = onBackAdminTab)
                                    AdminPanelRoute(
                                        sessionKey = sessionKey,
                                        onBack = onBackAdminTab,
                                        openSection = adminRequestedSection,
                                        onOpenSectionHandled = { adminRequestedSection = null },
                                    )
                                }
                            }
                        }
                    }

                    if (updateAvailable && showMandatoryUpdateDialog) {
                        AlertDialog(
                            onDismissRequest = { /* Bloqueo obligatorio: tocar fuera no descarta */ },
                            title = {
                                Text(
                                    text = stringResource(R.string.mandatory_update_title),
                                    fontWeight = FontWeight.Bold,
                                )
                            },
                            text = {
                                Text(
                                    text = stringResource(R.string.mandatory_update_message),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            confirmButton = {
                                Button(
                                    onClick = { showMandatoryUpdateDialog = false },
                                ) {
                                    Text(
                                        text = stringResource(R.string.mandatory_update_action),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            },
                        )
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
 * The shell keeps the branded header with the menu and profile actions at the top, followed by an active
 * Material3 [PrimaryScrollableTabRow] showing the destinations permitted for [role] by [RolePolicy] and a
 * drawer listing every surface the role may open, and renders [content] in the remaining body space.
 */
@Composable
fun MainAppShell(
    role: String?,
    currentTab: MainActivity.Tab,
    onSelectTab: (MainActivity.Tab) -> Unit,
    onOpenProfile: () -> Unit,
    // El aviso del invitado ofrece ir al login, y el login vive en MainActivity, no acá: el callback
    // entra por parámetro. Con default para que las pruebas del armazón no tengan que saber de sesiones.
    onGoToLogin: () -> Unit = {},
    updateAvailable: Boolean = false,
    modifier: Modifier = Modifier,
    // La superposición que ocupa la pantalla, si hay una: sin esto el cajón marcaría como elegido el
    // módulo padre del histórico que se está leyendo, y dos filas quedarían encendidas a la vez.
    currentOverlay: MainActivity.Overlay? = null,
    tabDestinations: List<MainActivity.Destination> = MainActivity.TAB_DESTINATIONS,
    drawerEntries: List<MainActivity.DrawerEntry> = MainActivity.DRAWER_ENTRIES,
    // El cajón llega a superficies que no son pestañas -- tres históricos y la auditoría --, así que el
    // armazón avisa el destino entero y deja a la actividad decidir cómo se abre. El default sirve a las
    // pruebas que sólo quieren ver la fila moverse.
    onSelectDrawerEntry: (MainActivity.Destination) -> Unit = { onSelectTab(it.target.tab) },
    drawerState: DrawerState = rememberDrawerState(initialValue = DrawerValue.Closed),
    content: @Composable () -> Unit,
) {
    // El aviso del invitado, en un solo lugar: puede intentar una mutación desde cualquier pantalla,
    // así que el rechazo no puede depender de que cada pantalla se acuerde de mostrarlo. Se atienden
    // los dos momentos -- el aviso que llega en vivo y el que esperó a que esto se montara -- porque
    // un rechazo ocurrido durante el arranque no puede perderse en silencio.
    var guestReadonlyNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        guestReadonlyNotice = GuestReadonlyNotice.takePending()
        GuestReadonlyNotice.messages.collect { guestReadonlyNotice = it }
    }
    guestReadonlyNotice?.let { message ->
        AlertDialog(
            onDismissRequest = { guestReadonlyNotice = null },
            // El candado en el color primario: el aviso se lee de un vistazo, sin depender del texto.
            icon = {
                Icon(
                    // painterResource y no Icons.Filled.Lock: el proyecto no depende de
                    // material-icons, y sumar una librería entera por un candado sería pagar para
                    // siempre por un adorno. El vector vive en res/drawable/ic_lock.xml.
                    painter = painterResource(R.drawable.ic_lock),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
            },
            title = {
                Text(
                    text = stringResource(R.string.guest_readonly_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.guest_readonly_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // El mensaje es el del servidor, sin traducir: es el literal de la directiva y una
                    // sola cadena para los dos lados. Va en su propio contenedor suave, como en la web.
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { guestReadonlyNotice = null }) {
                    Text(stringResource(R.string.guest_readonly_dismiss))
                }
            },
            // El secundario lleva al login: limpia el aviso antes de salir, si no reaparecería al llegar.
            dismissButton = {
                TextButton(
                    onClick = {
                        guestReadonlyNotice = null
                        onGoToLogin()
                    },
                ) {
                    Text(stringResource(R.string.guest_readonly_go_to_login))
                }
            },
        )
    }

    val visibleTabs = remember(role, tabDestinations) {
        val allowed = RolePolicy.visibleEntryPoints(role)
        tabDestinations.filter { it.entryPoint in allowed }
    }
    val visibleDrawer = remember(role, drawerEntries) {
        val allowed = RolePolicy.visibleEntryPoints(role)
        val shown = mutableListOf<MainActivity.DrawerEntry>()
        // Un encabezado se dibuja sólo si alguna fila lo sigue: el filtro por rol puede vaciar un grupo
        // entero, y un título sobre nada es una promesa que la pantalla no cumple.
        var pendingSection: MainActivity.DrawerEntry.Section? = null
        drawerEntries.forEach { entry ->
            when (entry) {
                is MainActivity.DrawerEntry.Section -> pendingSection = entry
                is MainActivity.DrawerEntry.Link -> if (entry.destination.entryPoint in allowed) {
                    pendingSection?.let {
                        shown += it
                        pendingSection = null
                    }
                    shown += entry
                }
            }
        }
        shown
    }
    val selectedIndex = visibleTabs.indexOfFirst { it.target.tab == currentTab }.coerceAtLeast(0)
    val activeTarget = MainActivity.NavTarget(currentTab, currentOverlay)
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
                        visibleDrawer.forEach { entry ->
                            when (entry) {
                                is MainActivity.DrawerEntry.Section -> Text(
                                    text = stringResource(entry.label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(start = 28.dp, top = 16.dp, bottom = 8.dp),
                                )
                                is MainActivity.DrawerEntry.Link -> {
                                    val destination = entry.destination
                                    NavigationDrawerItem(
                                        label = {
                                            Text(
                                                text = stringResource(destination.label),
                                                style = MaterialTheme.typography.labelLarge,
                                            )
                                        },
                                        icon = {
                                            Icon(
                                                painter = painterResource(destination.icon),
                                                contentDescription = null,
                                                modifier = Modifier.size(24.dp),
                                            )
                                        },
                                        selected = destination.target == activeTarget,
                                        onClick = {
                                            onSelectDrawerEntry(destination)
                                            scope.launch { drawerState.close() }
                                        },
                                        modifier = Modifier
                                            .padding(NavigationDrawerItemDefaults.ItemPadding)
                                            .defaultMinSize(minHeight = 48.dp),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(16.dp))
                        // El credito va al pie del cajon desplegable: ahi no estorba a ninguna pantalla y no hay
                        // tres copias que puedan quedar viejas por separado.
                        DeveloperCredit()
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
                        IconButton(
                            onClick = { scope.launch { drawerState.open() } },
                            modifier = Modifier
                                .testTag("header_menu_button")
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_menu),
                                contentDescription = stringResource(R.string.nav_menu),
                                modifier = Modifier.size(24.dp),
                            )
                        }
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
                        IconButton(
                            onClick = onOpenProfile,
                            modifier = Modifier
                                .testTag("header_profile_button")
                                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
                        ) {
                            Box {
                                Icon(
                                    painter = painterResource(R.drawable.ic_profile),
                                    contentDescription = stringResource(R.string.profile_title),
                                    modifier = Modifier.size(24.dp),
                                )
                                // El punto de la actualización disponible: el perfil es donde se descarga,
                                // y el ícono lo avisa sin agregar una palabra al encabezado.
                                if (updateAvailable) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(8.dp)
                                            .background(MaterialTheme.colorScheme.error, CircleShape),
                                    )
                                }
                            }
                        }
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
                                    selected = currentTab == destination.target.tab,
                                    onClick = { onSelectTab(destination.target.tab) },
                                    modifier = Modifier.defaultMinSize(minHeight = 48.dp),
                                    text = {
                                        Text(
                                            text = stringResource(destination.label),
                                            maxLines = 1,
                                            style = if (currentTab == destination.target.tab) {
                                                MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold)
                                            } else {
                                                MaterialTheme.typography.labelLarge
                                            },
                                            color = if (currentTab == destination.target.tab) {
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
