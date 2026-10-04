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
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AdminPanelEntryPoint {
    fun authRepository(): AuthRepository
}

@Composable
fun AdminPanelScreen(
    onBack: () -> Unit,
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NavigationActionButton(onClick = onBack) {
                Text(stringResource(R.string.report_back))
            }
            Text(
                text = stringResource(R.string.admin_panel_title),
                style = MaterialTheme.typography.titleLarge,
            )
        }

        Text(
            text = stringResource(R.string.admin_panel_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (isAdmin) {
            // Categorias (Active)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenSection(AdminSection.CATEGORIES) },
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
                            text = stringResource(R.string.admin_section_categories),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AdminPrimaryButton(
                            onClick = { onOpenSection(AdminSection.CATEGORIES) },
                            text = stringResource(R.string.admin_access_section),
                        )
                    }
                    Text(
                        text = stringResource(R.string.admin_section_categories_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Veículos (Active)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenSection(AdminSection.VEHICLES) },
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
                            text = stringResource(R.string.admin_section_vehicles),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AdminPrimaryButton(
                            onClick = { onOpenSection(AdminSection.VEHICLES) },
                            text = stringResource(R.string.admin_access_section),
                        )
                    }
                    Text(
                        text = stringResource(R.string.admin_section_vehicles_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Horários de Carga (Active)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenSection(AdminSection.TIME_SLOTS) },
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
                            text = stringResource(R.string.admin_section_time_slots),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AdminPrimaryButton(
                            onClick = { onOpenSection(AdminSection.TIME_SLOTS) },
                            text = stringResource(R.string.admin_access_section),
                        )
                    }
                    Text(
                        text = stringResource(R.string.admin_section_time_slots_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Usuários (Active)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenSection(AdminSection.USERS) },
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
                            text = stringResource(R.string.admin_section_users),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AdminPrimaryButton(
                            onClick = { onOpenSection(AdminSection.USERS) },
                            text = stringResource(R.string.admin_access_section),
                        )
                    }
                    Text(
                        text = stringResource(R.string.admin_section_users_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Slice D: Auditoria (Active for Admin)
            Card(
                modifier = Modifier.fillMaxWidth(),
                onClick = { onOpenSection(AdminSection.AUDIT) },
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
                            text = stringResource(R.string.admin_section_audit),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        AdminPrimaryButton(
                            onClick = { onOpenSection(AdminSection.AUDIT) },
                            text = stringResource(R.string.admin_access_section),
                        )
                    }
                    Text(
                        text = stringResource(R.string.admin_section_audit_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
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

    if (!effectiveIsAdmin && selectedSection != null) {
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

    if (!effectiveIsAdmin) {
        AdminPanelScreen(
            onBack = handleBack,
            onOpenSection = { },
            modifier = modifier,
            isAdmin = false,
        )
    } else if (sectionContent != null && selectedSection != null) {
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
            else -> {
                AdminPanelScreen(
                    onBack = handleBack,
                    onOpenSection = { section -> selectedSection = section },
                    modifier = modifier,
                    isAdmin = true,
                )
            }
        }
    }
}
