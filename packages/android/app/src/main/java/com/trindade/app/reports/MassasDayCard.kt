package com.trindade.app.reports

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.contract.models.MassasReportDayView
import java.math.BigDecimal

/**
 * The day's massas, under the temperatures.
 *
 * The operator reads the morning's temperatures and then counts what came out, so the two blocks sit together
 * instead of a scroll apart. The card is permanent: a day with nothing recorded says so, because an absent card
 * and a card showing zero mean different things and only one of them is true.
 *
 * Nothing is added up here. `operators` already counts each launch once, and `balance` is the backend's own
 * comparison against the ERP; a number assembled on the phone would be a second opinion nobody asked for.
 */
@Composable
fun MassasDayCard(
    day: MassasReportDayView?,
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.report_massas_day_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface,
            )

            if (day == null || day.operators.isEmpty()) {
                Text(
                    text = stringResource(R.string.report_massas_day_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }

            Text(
                text = stringResource(R.string.report_massas_day_operators, day.operators.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            day.operators.forEach { operator ->
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
                        text = "${operator.boxes.toPlain()} cx · ${operator.units.toPlain()} un",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.report_massas_day_balance, balanceLabel(day.balance.differenceUnits)),
                style = MaterialTheme.typography.labelLarge,
                color = if (day.balance.differenceUnits.signum() == 0) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }
    }
}

/** A whole number without the trailing zeros `BigDecimal` carries from the wire. */
private fun BigDecimal.toPlain(): String = stripTrailingZeros().toPlainString()

private fun balanceLabel(difference: BigDecimal): String =
    if (difference.signum() == 0) "0 un" else "${if (difference.signum() > 0) "+" else ""}${difference.stripTrailingZeros().toPlainString()} un"
