package com.trindade.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.contract.models.MassasReportDayView
import com.trindade.app.contract.models.ReportReceipts
import java.math.BigDecimal

/**
 * Unified ERP movements card: Massas and Recebimento (S5, S6, S7).
 *
 * Combines both blocks under a single card:
 * 1. Massas do dia: total produced in ERP and breakdown per operator (clean, no local comparison balance bar).
 * 2. Recebimento de Insumos: incoming supplies registered in ERP.
 *
 * A single sync button in the header triggers reading from the ERP via bridge and refreshes both blocks at once.
 */
@Composable
fun ErpMovementsCard(
    massasDay: MassasReportDayView?,
    receipts: ReportReceipts?,
    isSyncing: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header with unified title and single [ 🔄 Sincronizar com ERP ] button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.report_erp_movements_title),
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                OutlinedButton(
                    onClick = onSync,
                    enabled = !isSyncing,
                    modifier = Modifier.defaultMinSize(minHeight = 44.dp),
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text(
                            text = "🔄 " + stringResource(R.string.report_erp_sync),
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }

            HorizontalDivider()

            // Block 1: Massas do dia
            Text(
                text = stringResource(R.string.report_massas_day_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )

            if (massasDay == null || massasDay.operators.isEmpty()) {
                Text(
                    text = stringResource(R.string.report_massas_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.report_massas_day_operators, massasDay.operators.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                massasDay.operators.forEach { operator ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = operator.displayName,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${operator.boxes.stripZeros()} cx · ${operator.units.stripZeros()} un",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            HorizontalDivider()

            // Block 2: Recebimento de Insumos
            Text(
                text = stringResource(R.string.report_receipts_title),
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.primary,
            )

            val receiptList = receipts?.receipts.orEmpty()
            if (receiptList.isEmpty()) {
                Text(
                    text = stringResource(R.string.report_receipts_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                receiptList.forEach { receipt ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = receipt.productDesc.ifBlank { receipt.productCode },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (receipt.operatorName.isNotBlank()) {
                                Text(
                                    text = receipt.operatorName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = stringResource(R.string.report_receipts_units, receipt.quantity.stripZeros()),
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun BigDecimal.stripZeros(): String = stripTrailingZeros().toPlainString()
