package com.trindade.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * A Material 3 number stepper control with accessible touch targets >= 48dp.
 *
 * Provides decrement (-) and increment (+) outlined controls with clear bounds,
 * disabled states at limits, and a centrally displayed value.
 */
@Composable
fun NumberStepper(
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    minValue: Int = 1,
    maxValue: Int = 99,
    enabled: Boolean = true,
    step: Int = 1,
    valuePrefix: String = "",
    valueSuffix: String = "",
) {
    val canDecrement = enabled && value - step >= minValue
    val canIncrement = enabled && value + step <= maxValue

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            onClick = { if (canDecrement) onValueChange(value - step) },
            enabled = canDecrement,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (canDecrement) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("-", style = MaterialTheme.typography.titleMedium)
        }

        Text(
            text = "$valuePrefix$value$valueSuffix",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .widthIn(min = 40.dp)
                .padding(horizontal = 8.dp),
            textAlign = TextAlign.Center,
        )

        OutlinedButton(
            onClick = { if (canIncrement) onValueChange(value + step) },
            enabled = canIncrement,
            modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
            border = BorderStroke(
                width = 1.dp,
                color = if (canIncrement) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outline.copy(alpha = 0.38f),
            ),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text("+", style = MaterialTheme.typography.titleMedium)
        }
    }
}
