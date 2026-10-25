package com.trindade.app.stock

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.StockDay
import com.trindade.app.contract.models.StockDayProductsInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The past day as a reading, drawn rather than described.
 *
 * The rule this file exists for is an absence: the history reports the day and does not edit it, so the
 * quantity field, "Adicionar" and "Zerar" cannot be on it. Testing that a screen stopped offering something
 * is the only way to keep it from quietly coming back with the next refactor -- a test that only asserted the
 * summary would pass just as well with a second calculator bolted underneath it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StockHistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    private val canudo = StockDayProductsInner(
        code = "787", name = "CANUDO", category = "Terceirizados", unitsPerBox = 30, boxes = 5, units = 150
    )
    private val massa = StockDayProductsInner(
        code = "1047", name = "MASSA ROLO 2KG", category = "Massas", unitsPerBox = 6, boxes = 2, units = 12
    )

    private fun day() = StockDay(
        date = "2026-10-23",
        products = listOf(canudo, massa),
        totalBoxes = 7,
        totalUnits = 162,
    )

    private fun render(
        state: StockHistoryViewModel.UiState,
        onPickDate: () -> Unit = {},
        onDaySelected: (String) -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                StockHistoryContent(
                    state = state,
                    onBack = {},
                    onPickDate = onPickDate,
                    onDaySelected = onDaySelected,
                    onNumberChange = {},
                    onWhatsApp = {},
                    onEmail = {},
                )
            }
        }
    }

    @Test
    fun `the day reads as the collective summary with the server totals`() {
        render(StockHistoryViewModel.UiState(date = "2026-10-23", day = day()))

        composeRule.onNodeWithText(copy(R.string.stock_collective_summary)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("CANUDO").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_summary_boxes, 5)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("MASSA ROLO 2KG").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_day_total)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_day_total_format, 7, 162)).assertIsDisplayed()
    }

    @Test
    fun `the history offers no calculator to correct the day with`() {
        render(StockHistoryViewModel.UiState(date = "2026-10-23", day = day()))

        composeRule.onAllNodesWithText(copy(R.string.stock_add_button)).assertCountEquals(0)
        composeRule.onAllNodesWithText(copy(R.string.stock_reset_button)).assertCountEquals(0)
        composeRule.onNodeWithText(copy(R.string.stock_history_subtitle_readonly)).assertIsDisplayed()
    }

    @Test
    fun `sending the day out stays one tap away`() {
        render(StockHistoryViewModel.UiState(date = "2026-10-23", day = day()))

        composeRule.onNodeWithText(copy(R.string.whatsapp_send_button)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.stock_send_email_button)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the date opens the calendar instead of being typed`() {
        var picked = 0
        render(StockHistoryViewModel.UiState(date = "2026-10-23", day = day()), onPickDate = { picked++ })

        composeRule.onNodeWithText(copy(R.string.stock_pick_date)).performClick()
        composeRule.onNodeWithText("2026-10-23").performClick()
        org.junit.Assert.assertEquals(2, picked)
    }
}
