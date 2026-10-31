package com.trindade.app.massas

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.trindade.app.R
import com.trindade.app.ui.UiText
import com.trindade.app.ui.resolve

/** The card's spinner, so a test can tell "syncing" from "nothing happened". */
const val MASSAS_SYNC_SPINNER_TAG: String = "massas-sync-erp-progress"

/** The sentence that explains a withheld sync, and its own tag so it is asserted and not inferred. */
const val MASSAS_SYNC_HINT_TAG: String = "massas-sync-erp-hint"

/** The answer to the last press, drawn under the action that produced it. */
const val MASSAS_SYNC_RESULT_TAG: String = "massas-sync-erp-result"

/**
 * The ERP sync of the day the launch screen is showing.
 *
 * It carries its own state rather than leaving the screen to paint it around the card, because the two
 * things an operator needs after pressing the action -- that it is running, and why it cannot run -- have
 * to appear *here*: the module's banners are drawn above a column taller than a phone screen, so a
 * sentence published there is a sentence scrolled out of sight from the button that asked for it.
 *
 * The action therefore stays enabled when the bridge is not configured. A disabled button over a
 * sentence nobody has read is the same experience as a button that does nothing, so the press is what
 * surfaces [R.string.massas_aero_not_configured] -- and the sentence is drawn under the row as well, so
 * the reason is readable before the press and not only after it.
 */
@Composable
fun MassasErpSyncCard(
    syncedAt: String?,
    aeroReady: Boolean,
    isSyncing: Boolean,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
    /** What the last press answered: how many records were stored, or why nothing was. */
    result: UiText? = null,
    resultIsError: Boolean = false,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = syncedAt?.let { stringResource(R.string.massas_synced_at, it) }
                        ?: stringResource(R.string.massas_never_synced),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(
                    onClick = onSync,
                    // Only the sync itself withholds the action: a bridge without settings is a reason to
                    // say so when pressed, not a reason to remove the press.
                    enabled = !isSyncing,
                    modifier = Modifier
                        .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                        .testTag(MASSAS_SYNC_TAG),
                ) {
                    if (isSyncing) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(16.dp)
                                .testTag(MASSAS_SYNC_SPINNER_TAG),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.massas_sync_erp),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
            }
            if (!aeroReady) {
                Text(
                    text = stringResource(R.string.massas_aero_not_configured),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag(MASSAS_SYNC_HINT_TAG),
                )
            } else if (result != null) {
                // The result lives here, under the button, and not in the module's banners at the top of a
                // column taller than a phone screen: the operator pressed this action and reads its answer
                // in the same glance.
                Text(
                    text = (if (resultIsError) "⚠ " else "✓ ") + result.resolve(),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag(MASSAS_SYNC_RESULT_TAG),
                )
            }
        }
    }
}
