package com.trindade.app.massas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trindade.app.R

/** The one role name the API answers that may import anybody's seat. */
private const val ADMIN_ROLE = "Administrador"

/**
 * One day of the plant, no matter which screen wrote each box.
 *
 * The two sides are shown together because the question the operator asks is "what is missing", not "what did
 * I do": a reconciled row proves the ERP already has it, a pending row is the one to resend, and a row that only
 * exists in the ERP is either the tablet's work or a supply this screen must not import. The badge carries the
 * state, so reading the table does not require knowing which column came from where.
 */
@Composable
fun MassasUnifiedTable(
    state: MassasLaunchViewModel.UiState,
    onSend: (MassasUnifiedRow) -> Unit,
    onAcknowledge: (MassasUnifiedRow) -> Unit,
    onImport: (MassasUnifiedRow) -> Unit,
    onCreateOperator: ((MassasUnifiedRow) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val rows = state.unifiedRows
    val balance = state.unifiedBalance
    val viewer = state.currentUser
    val isAdmin = viewer?.role == ADMIN_ROLE

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.massas_unified_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.massas_empty_day),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                BalanceBar(balance = balance, modifier = Modifier.fillMaxWidth())
                rows.forEach { row ->
                    val isInactive = row.operatorName in state.inactiveOperators
                    val canImport = canImportRow(
                        row = row,
                        viewerId = viewer?.id,
                        isAdmin = isAdmin,
                    )
                    val canCreate = canCreateOperatorRow(
                        row = row,
                        isAdmin = isAdmin,
                        isInactive = isInactive,
                    )
                    UnifiedRow(
                        row = row,
                        readOnly = state.isReadOnly,
                        isAdmin = isAdmin,
                        canImport = canImport,
                        canCreate = canCreate,
                        isInactive = isInactive,
                        onSend = { onSend(row) },
                        onAcknowledge = { onAcknowledge(row) },
                        onImport = { onImport(row) },
                        onCreateOperator = { onCreateOperator?.invoke(row) },
                    )
                }
            }
        }
    }
}

/**
 * What the day is missing, in one line.
 *
 * A difference of zero is said out loud instead of drawing nothing: "todo conciliado" is the answer the operator
 * came for, and an empty space reads as a failure to load.
 */
@Composable
private fun BalanceBar(balance: MassasBalance, modifier: Modifier = Modifier) {
    val difference = balance.difference
    val color = when {
        difference == 0 -> MaterialTheme.colorScheme.secondaryContainer
        difference < 0 -> MaterialTheme.colorScheme.errorContainer
        else -> MaterialTheme.colorScheme.tertiaryContainer
    }
    val text = when {
        difference == 0 -> stringResource(R.string.massas_balance_even)
        difference < 0 -> stringResource(R.string.massas_balance_missing, -difference)
        else -> stringResource(R.string.massas_balance_extra, difference)
    }
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color,
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
        )
    }
}

/**
 * One row: who, what, how many, and the one action that state allows.
 *
 * A box that does not fit whole is not importable and says nothing about why: the plant's own seat stays in the
 * ERP untouched, and inventing a half box would create a launch nobody counted.
 */
@Composable
private fun UnifiedRow(
    row: MassasUnifiedRow,
    readOnly: Boolean,
    isAdmin: Boolean,
    canImport: Boolean,
    canCreate: Boolean,
    isInactive: Boolean,
    onSend: () -> Unit,
    onAcknowledge: () -> Unit,
    onImport: () -> Unit,
    onCreateOperator: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StateBadge(state = row.state)
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.productName.ifBlank { row.productCode },
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "${row.operatorName} · ${row.boxes} cx / ${row.units} un",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (!readOnly) {
            when (row.state) {
                MassasRowState.RECONCILED -> Unit
                MassasRowState.PENDING_ERP -> ActionRow(
                    primaryLabel = stringResource(R.string.massas_action_send),
                    onPrimary = onSend,
                    secondaryLabel = if (row.tabletAcked) null else stringResource(R.string.massas_action_on_tablet),
                    onSecondary = onAcknowledge,
                )
                MassasRowState.ERP_ONLY -> when {
                    canImport -> {
                        ActionRow(
                            primaryLabel = stringResource(R.string.massas_action_import),
                            onPrimary = onImport,
                            secondaryLabel = null,
                            onSecondary = {},
                        )
                    }
                    canCreate -> {
                        ActionRow(
                            primaryLabel = stringResource(R.string.massas_action_create_operator, row.operatorName),
                            onPrimary = onCreateOperator,
                            secondaryLabel = null,
                            onSecondary = {},
                        )
                    }
                    isInactive -> {
                        Text(
                            text = stringResource(R.string.massas_operator_inactive),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    !isAdmin && row.ownerUserId == null -> {
                        Text(
                            text = stringResource(R.string.massas_operator_not_linked),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ActionRow(
    primaryLabel: String,
    onPrimary: () -> Unit,
    secondaryLabel: String?,
    onSecondary: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        RowAction(label = primaryLabel, onClick = onPrimary)
        if (secondaryLabel != null) {
            RowAction(label = secondaryLabel, onClick = onSecondary)
        }
    }
}

/** A 44dp-high tap target that reads as a link, not as a filled button: the row is already dense. */
@Composable
private fun RowAction(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 44.dp)
            .padding(end = 4.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * The state as a coloured word.
 *
 * Colour alone would be invisible to a colour-blind operator and unreadable in the sun, so the badge is text
 * first and colour second; the tokens are the theme's, never a literal green or amber.
 */
@Composable
private fun StateBadge(state: MassasRowState) {
    val (label, container) = when (state) {
        MassasRowState.RECONCILED ->
            stringResource(R.string.massas_state_reconciled) to MaterialTheme.colorScheme.secondaryContainer
        MassasRowState.PENDING_ERP ->
            stringResource(R.string.massas_state_pending) to MaterialTheme.colorScheme.errorContainer
        MassasRowState.ERP_ONLY ->
            stringResource(R.string.massas_state_erp_only) to MaterialTheme.colorScheme.tertiaryContainer
    }
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = container,
        border = BorderStroke(1.dp, Color.Transparent),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier
                .heightIn(min = 20.dp)
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** The tag the sync tests look for, kept next to the composable that draws it. */
const val MASSAS_SYNC_TAG: String = "massas-sync-erp"
