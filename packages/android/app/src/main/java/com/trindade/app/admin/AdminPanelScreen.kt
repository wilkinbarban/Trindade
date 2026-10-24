package com.trindade.app.admin

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.auth.AuthRepository
import com.trindade.app.auth.RolePolicy
import com.trindade.app.ui.components.AdminPrimaryButton
import com.trindade.app.ui.components.NavigationActionButton
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

enum class AdminSection {
    CATEGORIES,
    VEHICLES,
    TIME_SLOTS,
    USERS,
    AUDIT,

    /**
     * The two catalog surfaces the server's `catalogGuard` opens to both roles.
     *
     * They are here, and not in the tab row, because they are configuration of the operation rather than
     * an operation of their own: a worker who keeps the task types current and the administrators who own
     * the rest of the panel reach the same two screens from the same place.
     */
    TASKS,
    DRIVERS,
}

/** The sections behind `adminGuard`, as opposed to the two `catalogGuard` shares. */
fun AdminSection.isAdminOnly(): Boolean = when (this) {
    AdminSection.CATEGORIES, AdminSection.VEHICLES, AdminSection.TIME_SLOTS,
    AdminSection.USERS, AdminSection.AUDIT -> true
    AdminSection.TASKS, AdminSection.DRIVERS -> false
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AdminPanelEntryPoint {
    fun authRepository(): AuthRepository
}

/**
 * The panel's door: one card per section, the two shared ones first.
 *
 * The cards are drawn from the same literal rule the server's guards use -- tasks and drivers for both
 * roles, everything else for the administrator -- and this screen only decides what is *offered*. A
 * hidden card is not authorization: every section still meets its own guard on the way in.
 */
@Composable
fun AdminPanelScreen(
    onOpenSection: (AdminSection) -> Unit,
    modifier: Modifier = Modifier,
    isAdmin: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(R.string.admin_panel_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Tarefas e Motoristas: catálogo compartilhado (`catalogGuard`), así que ninguna de las dos se
        // esconde por rol. Son también las que justifican que un trabajador tenga esta pestaña.
        AdminSectionCard(
            title = stringResource(R.string.admin_section_tasks),
            description = stringResource(R.string.admin_section_tasks_desc),
            onClick = { onOpenSection(AdminSection.TASKS) },
        )
        AdminSectionCard(
            title = stringResource(R.string.admin_section_drivers),
            description = stringResource(R.string.admin_section_drivers_desc),
            onClick = { onOpenSection(AdminSection.DRIVERS) },
        )

        if (isAdmin) {
            AdminSectionCard(
                title = stringResource(R.string.admin_section_categories),
                description = stringResource(R.string.admin_section_categories_desc),
                onClick = { onOpenSection(AdminSection.CATEGORIES) },
            )
            AdminSectionCard(
                title = stringResource(R.string.admin_section_vehicles),
                description = stringResource(R.string.admin_section_vehicles_desc),
                onClick = { onOpenSection(AdminSection.VEHICLES) },
            )
            AdminSectionCard(
                title = stringResource(R.string.admin_section_time_slots),
                description = stringResource(R.string.admin_section_time_slots_desc),
                onClick = { onOpenSection(AdminSection.TIME_SLOTS) },
            )
            AdminSectionCard(
                title = stringResource(R.string.admin_section_users),
                description = stringResource(R.string.admin_section_users_desc),
                onClick = { onOpenSection(AdminSection.USERS) },
            )
            AdminSectionCard(
                title = stringResource(R.string.admin_section_audit),
                description = stringResource(R.string.admin_section_audit_desc),
                onClick = { onOpenSection(AdminSection.AUDIT) },
            )
        }
    }
}

/** One section of the panel: its name, what it is for, and the way in. */
@Composable
private fun AdminSectionCard(
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                )
                AdminPrimaryButton(
                    onClick = onClick,
                    text = stringResource(R.string.admin_access_section),
                )
            }
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AdminPanelRoute(
    sessionKey: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialSection: AdminSection? = null,
    authRepository: AuthRepository? = null,
    isAdmin: Boolean? = null,
    sectionContent: (@Composable (section: AdminSection, onBack: () -> Unit) -> Unit)? = null,
    // La sección que el cajón pidió abrir (hoy, Auditoria). Se consume una vez y se avisa, para que el
    // pedido no vuelva a imponerse cuando el operador elige otra sección más tarde.
    openSection: AdminSection? = null,
    onOpenSectionHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val effectiveIsAdmin = isAdmin ?: run {
        val repo = authRepository ?: remember(context) {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                AdminPanelEntryPoint::class.java,
            ).authRepository()
        }
        repo.sessionRole() == RolePolicy.ADMIN
    }

    var selectedSection by rememberSaveable(
        stateSaver = Saver<AdminSection?, String>(
            save = { it?.name ?: "" },
            restore = { if (it.isEmpty()) null else runCatching { AdminSection.valueOf(it) }.getOrNull() },
        ),
    ) {
        mutableStateOf(if (effectiveIsAdmin) initialSection else null)
    }

    LaunchedEffect(openSection) {
        if (openSection != null) {
            selectedSection = openSection
            onOpenSectionHandled()
        }
    }

    if (!effectiveIsAdmin && selectedSection?.isAdminOnly() == true) {
        selectedSection = null
    }

    val handleBack = {
        if (selectedSection != null) {
            selectedSection = null
        } else {
            onBack()
        }
    }

    BackHandler(enabled = true) {
        handleBack()
    }

    if (sectionContent != null && selectedSection != null) {
        sectionContent(selectedSection!!) { selectedSection = null }
    } else {
        when (selectedSection) {
            AdminSection.CATEGORIES -> {
                CategoriesRoute(
                    sessionKey = sessionKey,
                    onBack = { selectedSection = null },
                    modifier = modifier,
                )
            }
            AdminSection.VEHICLES -> {
                VehiclesRoute(
                    sessionKey = sessionKey,
                    onBack = { selectedSection = null },
                    modifier = modifier,
                )
            }
            AdminSection.TIME_SLOTS -> {
                TimeSlotsAdminRoute(
                    sessionKey = sessionKey,
                    onBack = { selectedSection = null },
                    modifier = modifier,
                )
            }
            AdminSection.USERS -> {
                UsersRoute(
                    sessionKey = sessionKey,
                    onBack = { selectedSection = null },
                    modifier = modifier,
                )
            }
            AdminSection.AUDIT -> {
                AuditRoute(
                    sessionKey = sessionKey,
                    onBack = { selectedSection = null },
                    modifier = modifier,
                )
            }
            // Las dos del catálogo compartido: `catalogGuard` las abre a los dos roles, así que se
            // dibujan para quien haya entrado, y la llamada sigue encontrando su propio guard al salir.
            AdminSection.TASKS -> {
                TasksRoute(
                    sessionKey = sessionKey,
                    onBack = { selectedSection = null },
                    modifier = modifier,
                )
            }
            AdminSection.DRIVERS -> {
                DriversRoute(
                    sessionKey = sessionKey,
                    onBack = { selectedSection = null },
                    modifier = modifier,
                )
            }
            else -> {
                AdminPanelScreen(
                    onOpenSection = { section -> selectedSection = section },
                    modifier = modifier,
                    isAdmin = effectiveIsAdmin,
                )
            }
        }
    }
}
