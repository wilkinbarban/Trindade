package com.trindade.app.massas

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.ui.UiText
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the sync card says before, during and after the press.
 *
 * The complaint behind this card is about visibility, not styling: the module's banners sit above a column
 * taller than a phone screen, so the answer to a button pressed at the bottom was a sentence scrolled out of
 * sight. These tests pin the three states that have to be readable *in the card* -- the reason a sync cannot
 * run, that it is running, and what it answered -- and the one rule that follows from them: the action is
 * never disabled for a missing configuration, because the press is what surfaces the reason.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [28], qualifiers = "w375dp-h800dp")
class MassasErpSyncCardTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun draw(
        aeroReady: Boolean = true,
        isSyncing: Boolean = false,
        result: UiText? = null,
        resultIsError: Boolean = false,
    ) {
        composeRule.setContent {
            TrindadeTheme {
                MassasErpSyncCard(
                    syncedAt = null,
                    aeroReady = aeroReady,
                    isSyncing = isSyncing,
                    onSync = {},
                    result = result,
                    resultIsError = resultIsError,
                )
            }
        }
    }

    @Test
    fun `a bridge without settings says why and keeps the action pressable`() {
        draw(aeroReady = false)

        composeRule.onNodeWithTag(MASSAS_SYNC_HINT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText(string(R.string.massas_aero_not_configured)).assertIsDisplayed()
        composeRule.onNodeWithTag(MASSAS_SYNC_TAG).assertIsEnabled()
    }

    @Test
    fun `a configured bridge offers the action and no explanation`() {
        draw(aeroReady = true)

        composeRule.onNodeWithTag(MASSAS_SYNC_TAG).assertIsEnabled()
        composeRule.onNodeWithText(string(R.string.massas_sync_erp)).assertIsDisplayed()
        assertTrue(composeRule.onAllNodesWithTag(MASSAS_SYNC_HINT_TAG).fetchSemanticsNodes().isEmpty())
    }

    /** While it runs, the button carries a spinner and refuses a second press. */
    @Test
    fun `a running sync shows a spinner and withholds the action`() {
        draw(isSyncing = true)

        composeRule.onNodeWithTag(MASSAS_SYNC_SPINNER_TAG).assertIsDisplayed()
        composeRule.onNodeWithTag(MASSAS_SYNC_TAG).assertIsNotEnabled()
    }

    @Test
    fun `a finished sync answers under the action that asked for it`() {
        draw(result = UiText.of(R.string.massas_aero_sync_done, 12), resultIsError = false)

        composeRule.onNodeWithTag(MASSAS_SYNC_RESULT_TAG).assertIsDisplayed()
        composeRule.onNodeWithText("✓ Movimentos do ERP sincronizados: 12 registro(s).").assertIsDisplayed()
    }

    @Test
    fun `a failed sync answers with its reason under the action`() {
        draw(result = UiText.of(R.string.massas_aero_unreachable), resultIsError = true)

        composeRule.onNodeWithText("⚠ " + string(R.string.massas_aero_unreachable)).assertIsDisplayed()
    }

    private fun string(resId: Int, vararg args: Any): String =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getString(resId, *args)
}
