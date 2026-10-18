package com.trindade.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import com.trindade.app.network.GuestReadonlyNotice
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
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
import com.trindade.app.assai.AssaiCalculatorScreen
import com.trindade.app.stock.StockCalculatorScreen
import com.trindade.app.stock.StockHistoryScreen
import com.trindade.app.ui.components.DeveloperCredit
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
        ASSAI(R.string.nav_assai),
        STOCK_COUNTER(R.string.nav_stock_counter),
        STOCK_HISTORY(R.string.nav_stock_history),
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
            RolePolicy.EntryPoint.ASSAI_ORDER to Tab.ASSAI,
            RolePolicy.EntryPoint.STOCK_COUNTER to Tab.STOCK_COUNTER,
            RolePolicy.EntryPoint.STOCK_HISTORY to Tab.STOCK_HISTORY,
        )
    }

    private val destinations = DEFAULT_DESTINATIONS

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

                    LaunchedEffect(signedIn) {
                        if (signedIn) {
                            val release = updateRepository.latestRelease()
                            if (release != null) {
                                val availability = com.trindade.app.update.UpdateDetector.checkUpdate(
                                    release = release,
                                    currentVersionName = com.trindade.app.BuildConfig.APP_VERSION_NAME,
                                    currentBuildTimeMillis = com.trindade.app.BuildConfig.BUILD_TIME,
                                )
                                updateAvailable = availability.isUpdateAvailable
                            }
                        }
                    }
                    // Para cerrar la sesión de verdad al salir al login: limpiar sólo el estado de la
                    // pantalla dejaría el token vivo en el dispositivo.
                    val sessionScope = rememberCoroutineScope()

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
                            onGoToLogin = {
                                // El aviso manda al login y la sesión se cierra de verdad: endSession limpia
                                // el estado de la pantalla y el repositorio borra el token del dispositivo, que
                                // es lo que el botón promete.
                                endSession()
                                sessionScope.launch { authRepository.logout() }
                            },
                            updateAvailable = updateAvailable,
                            destinations = destinations,
                            drawerState = drawerState,
                        ) {
                            when (tab) {
                                // Las tres superficies nuevas se dibujan sin argumentos: cada una resuelve su
                                // ViewModel por Hilt, y la calculadora de Assaí no toca la red en absoluto.
                                Tab.ASSAI -> AssaiCalculatorScreen()
                                // El histórico propaga el rol resuelto de sesión para que el
                                // Administrador pueda realizar correcciones sobre días anteriores.
                                Tab.STOCK_HISTORY -> StockHistoryScreen(role = role)
                                Tab.STOCK_COUNTER -> StockCalculatorScreen()
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
    // El aviso del invitado ofrece ir al login, y el login vive en MainActivity, no acá: el callback
    // entra por parámetro. Con default para que las pruebas del armazón no tengan que saber de sesiones.
    onGoToLogin: () -> Unit = {},
    updateAvailable: Boolean = false,
    modifier: Modifier = Modifier,
    destinations: List<Pair<RolePolicy.EntryPoint, MainActivity.Tab>> = MainActivity.DEFAULT_DESTINATIONS,
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
                            text = if (updateAvailable) "${stringResource(R.string.profile_title)} •" else stringResource(R.string.profile_title),
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
