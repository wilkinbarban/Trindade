package com.trindade.app.reports

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * Where the header's two pieces sit, at the widths the crew's phones and the plant's tablets really have.
 *
 * The complaint this pins is a measurement, not a style preference: on an iPhone the title was drawn beside
 * the sync button with four words to a line, and the button itself was pushed out of the card's padding. So
 * the assertion is about geometry -- the action must sit **below** the title when there is no room beside it,
 * and beside it when there is.
 *
 * The width is forced rather than left to the device, because the card is the same composable on both: what
 * changes its shape has to be the space it is given.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w800dp-h1000dp")
class ErpMovementsCardLayoutTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    private fun drawCardIn(width: Int) {
        composeRule.setContent {
            TrindadeTheme {
                Box(modifier = Modifier.width(width.dp)) {
                    ErpMovementsCard(
                        massasDay = null,
                        receipts = null,
                        isSyncing = false,
                        onSync = {},
                    )
                }
            }
        }
    }

    @Test
    fun `on a phone the sync action sits below the title rather than beside it`() {
        // 360dp: the width of the handset the complaint came from, minus nothing -- the card is already the
        // full content width there.
        drawCardIn(360)

        val title = composeRule
            .onNodeWithText(context.getString(R.string.report_erp_movements_title))
            .getUnclippedBoundsInRoot()
        val button = composeRule
            .onNodeWithText(context.getString(R.string.report_erp_sync), substring = true)
            .getUnclippedBoundsInRoot()

        assertTrue(
            "the action must start below the title, not beside it; title=$title button=$button",
            button.top >= title.bottom,
        )
    }

    @Test
    fun `on a tablet the sync action returns beside the title`() {
        drawCardIn(720)

        val title = composeRule
            .onNodeWithText(context.getString(R.string.report_erp_movements_title))
            .getUnclippedBoundsInRoot()
        val button = composeRule
            .onNodeWithText(context.getString(R.string.report_erp_sync), substring = true)
            .getUnclippedBoundsInRoot()

        // The same row: the action begins to the right of the title and is not pushed below it.
        assertTrue(
            "the action must sit to the right of the title; title=$title button=$button",
            button.left > title.left && button.top < title.bottom,
        )
    }
}
