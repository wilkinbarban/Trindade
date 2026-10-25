package com.trindade.app.ui.components

import android.content.Context
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The pair of header actions, measured against each other.
 *
 * The directive asks for the history button to be the visual twin of the help button, so this test is the
 * one that fails if either drifts: same pill, same row, one at each corner. It also holds the two sizes the
 * pair has to keep at once -- a 36dp pill inside a 48dp touch target -- because those are exactly the two
 * numbers a later "cleanup" is most likely to collapse into one.
 *
 * It deliberately does not assert colours: those belong to the theme, and a test that pinned them would fail
 * on a palette change that is not a regression.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ModuleHeaderActionsTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun assertPillHeight(tag: String, expected: Dp) {
        val height = composeRule.onNodeWithTag(tag, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
            .let { it.bottom - it.top }
        assertTrue(
            "Pill $tag must be $expected tall but was $height",
            height in (expected - 1.dp)..(expected + 1.dp),
        )
    }

    @Test
    fun `history and help keep the same 36dp pill inside a 48dp touch target`() {
        var historyTaps = 0
        var helpTaps = 0

        composeRule.setContent {
            TrindadeTheme {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ModuleHistoryButton(onClick = { historyTaps++ })
                    Spacer(modifier = Modifier.weight(1f))
                    ModuleHelpButton(onClick = { helpTaps++ })
                }
            }
        }

        val history = composeRule.onNodeWithText(context.getString(R.string.report_history))
        val help = composeRule.onNodeWithText(context.getString(R.string.help_button_label))
        val historyBounds = history.getUnclippedBoundsInRoot()
        val helpBounds = help.getUnclippedBoundsInRoot()

        // The text node carries the click action, so it is the touch target: 48dp in both directions.
        assertTrue(
            "The history touch target must be >= 48dp tall but was ${historyBounds.bottom - historyBounds.top}",
            historyBounds.bottom - historyBounds.top >= 48.dp,
        )
        assertTrue(
            "The history touch target must be >= 48dp wide but was ${historyBounds.right - historyBounds.left}",
            historyBounds.right - historyBounds.left >= 48.dp,
        )
        assertTrue(
            "The help touch target must be >= 48dp tall but was ${helpBounds.bottom - helpBounds.top}",
            helpBounds.bottom - helpBounds.top >= 48.dp,
        )

        // The pills are what you see, and they are the size the header was designed at -- the same for both.
        assertPillHeight(ModuleHistoryButtonPillTag, 36.dp)
        assertPillHeight(ModuleHelpButtonPillTag, 36.dp)

        // History at the start corner, help at the end corner: the row fills the screen and the two sit on
        // opposite sides of it, not next to each other.
        val rootBounds = composeRule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "History must start at the row's left edge: ${historyBounds.left} vs ${rootBounds.left}",
            (historyBounds.left - rootBounds.left).value in -1f..1f,
        )
        assertTrue(
            "Help must end at the row's right edge: ${helpBounds.right} vs ${rootBounds.right}",
            (helpBounds.right - rootBounds.right).value in -1f..1f,
        )

        history.performClick()
        help.performClick()
        assertTrue("History tap must reach its handler", historyTaps == 1)
        assertTrue("Help tap must reach its handler", helpTaps == 1)
    }

    @Test
    fun `the two actions do not overlap when the row also has a back button`() {
        var backTaps = 0
        var historyTaps = 0
        var helpTaps = 0

        composeRule.setContent {
            TrindadeTheme {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NavigationActionButton(
                        onClick = { backTaps++ },
                        text = context.getString(R.string.report_back),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    ModuleHistoryButton(onClick = { historyTaps++ })
                    Spacer(modifier = Modifier.weight(1f))
                    ModuleHelpButton(onClick = { helpTaps++ })
                }
            }
        }

        val back = composeRule.onNodeWithText(context.getString(R.string.report_back))
            .getUnclippedBoundsInRoot()
        val history = composeRule.onNodeWithText(context.getString(R.string.report_history))
            .getUnclippedBoundsInRoot()
        val help = composeRule.onNodeWithText(context.getString(R.string.help_button_label))
            .getUnclippedBoundsInRoot()

        assertTrue("Back must come first: ${back.left} vs ${history.left}", back.left < history.left)
        assertTrue("Back and history must not overlap: ${back.right} vs ${history.left}", back.right <= history.left)
        assertTrue("Help must stay on the right: ${history.right} vs ${help.left}", history.right < help.left)
        // Their baselines are the row's, so the three share a band: none of them drops out of the header.
        assertTrue(
            "The three actions must share one row band: ${back.bottom} vs ${help.bottom}",
            back.bottom == help.bottom && history.bottom == help.bottom,
        )
    }
}
