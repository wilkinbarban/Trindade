package com.trindade.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The credit that travels with the application.
 *
 * One composable rather than a line repeated in three screens, for the same reason the web keeps it in one
 * component: a credit written twice goes stale in one of the two places.
 *
 * It is deliberately small and quiet -- ten sp, the theme's own on-surface variant at a little over half
 * opacity, and centred -- because it stands at the foot of a screen somebody is working in. The colour
 * comes from the theme and not from a fixed grey: in a dark theme a fixed grey either disappears or shouts,
 * which is the opposite of what a credit should do.
 */
@Composable
fun DeveloperCredit(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Desenvolvido por Wilkin Barban Rosabal",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
        Text(
            text = "E-mail: wilkin.barban@gmail.com",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}
