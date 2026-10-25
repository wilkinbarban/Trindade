package com.trindade.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.trindade.app.R
import com.trindade.app.ui.theme.HelpAmber

data class HelpSection(
    val title: String,
    val description: String? = null,
    val steps: List<String>? = null,
)

/**
 * The shape of every header action: a 36dp pill inside a 48dp touch target.
 *
 * Two numbers, and each is there for a different reason. The pill is 36dp because that is the size the
 * header actions were designed at -- it sits next to a title, and two 36dp-tall neighbours read as one row
 * where 48dp buttons would fight the text for attention. The touch target is 48dp because that is the
 * minimum this app holds itself to everywhere else, and a 36dp pill with a 36dp target is a control that
 * looks fine and misses taps.
 *
 * The click lives on the outer box, not on the pill: that is what makes the two sizes possible at once. A
 * Material button paints its background at the size it measures, so forcing it to 36dp would also shrink
 * the area that answers a finger. Here the pill is drawn by the inner surface and the box around it takes
 * the tap, which is the same arrangement Material uses internally -- `minimumInteractiveComponentSize` pads
 * the touch area while the visual stays where it was designed.
 *
 * The pill's height is a minimum rather than a fixed height on purpose: at a large font scale the label
 * grows and the pill grows with it instead of clipping the word. The touch target is a minimum too.
 */
@Composable
private fun ModuleHeaderAction(
    onClick: () -> Unit,
    label: String,
    icon: Painter,
    tint: Color,
    pillTag: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            // Full round, the same shape `NavigationActionButton` takes from `OutlinedButton`: in a header the
            // two sit one next to the other, and an 8dp rectangle beside a pill reads as two button families.
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = CircleShape,
            color = Color.Transparent,
            // Same outline and same label colour as NavigationActionButton, the control these two sit next
            // to in every header: one border token for the row, so "Voltar" and the two actions don't read
            // as three different button families.
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            contentColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag(pillTag),
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 36.dp)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    painter = icon,
                    // The label sits right next to this icon: repeating it here would make a screen reader
                    // announce "Histórico Histórico".
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                )
            }
        }
    }
}

/** Test tag of the help pill inside its touch target: the box answers the tap, the pill is what you see. */
const val ModuleHelpButtonPillTag = "module-help-pill"

/** Test tag of the history pill inside its touch target. */
const val ModuleHistoryButtonPillTag = "module-history-pill"

/**
 * Header action to trigger contextual help in operational screens.
 */
@Composable
fun ModuleHelpButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.help_button_label),
) {
    ModuleHeaderAction(
        onClick = onClick,
        label = label,
        icon = painterResource(R.drawable.ic_help),
        tint = HelpAmber,
        pillTag = ModuleHelpButtonPillTag,
        modifier = modifier,
    )
}

/**
 * Header action to open a module's history, the twin of [ModuleHelpButton].
 *
 * Both are built by [ModuleHeaderAction], so they cannot drift apart: same pill, same icon size, same gap,
 * same touch target. What tells them apart is only what identifies them -- the history takes the icon of
 * the histories and this app's primary colour, help keeps the amber it has always had.
 */
@Composable
fun ModuleHistoryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(R.string.report_history),
) {
    ModuleHeaderAction(
        onClick = onClick,
        label = label,
        icon = painterResource(R.drawable.ic_history),
        tint = MaterialTheme.colorScheme.primary,
        pillTag = ModuleHistoryButtonPillTag,
        modifier = modifier,
    )
}

/**
 * Reusable contextual help dialog with Material 3 styling, amber accents,
 * scrollable steps/sections, and an "Entendido" close button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModuleHelpDialog(
    title: String,
    description: String,
    sections: List<HelpSection>,
    onDismissRequest: () -> Unit,
    subtitle: String? = null,
) {
    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.padding(16.dp),
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            // A dialog sits above the screen it covers, and in Material 3 that height is a tonal surface
            // plus elevation level 3 -- not plain white on a warm background, which read as a hole.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 24.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_help),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    if (description.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small)
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape = MaterialTheme.shapes.small)
                                .padding(12.dp),
                        ) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 18.sp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    sections.forEachIndexed { idx, section ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer, shape = CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "${idx + 1}",
                                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                                )
                            }
                            if (!section.description.isNullOrBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = section.description,
                                    style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 30.dp),
                                )
                            }
                            if (!section.steps.isNullOrEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Column(modifier = Modifier.padding(start = 30.dp)) {
                                    section.steps.forEach { step ->
                                        Row(modifier = Modifier.padding(vertical = 2.dp)) {
                                            Text(
                                                text = "• ",
                                                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            Text(
                                                text = step,
                                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = 16.sp),
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Footer
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                ) {
                    Spacer(modifier = Modifier.weight(1f))
                    Button(
                        onClick = onDismissRequest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            text = stringResource(R.string.help_dialog_understood),
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                        )
                    }
                }
            }
        }
    }
}
