package com.trindade.app.dashboard

import android.content.Context
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode

/**
 * The dashboard, rendered rather than read.
 *
 * The screen is rendered directly with a state built by hand -- no ViewModel, no Hilt -- because that is
 * what its stateless shape is for, and it is the only way this lane can reach the states that matter:
 * the cards the operator reads, the two cards that are doors, and the two states with nothing to draw.
 * The last pair is the reason the lane exists at all. Every count in a `DashboardSummary` is a
 * non-nullable `Int`, so a screen that defaulted instead of reporting would draw a plausible day of
 * zeroes for a read that never arrived, and nothing in a JVM test of the view model can see what a
 * screen draws.
 *
 * Four things about this file are deliberate, and the first two are the lane's own conventions:
 *
 *  * `GraphicsMode.NATIVE` is what makes the rendering real: text is measured by the framework's layout
 *    instead of by a shadow that returns a constant. This test asserts that nodes are *displayed*, and a
 *    node's size is what that turns on, so a lane that did not measure text could not make the claim.
 *  * The copy is looked up from the resources the app ships -- `copy(R.string.x, ...)` -- rather than
 *    typed here, so a change to a label or to a count's sentence fails this test instead of passing
 *    silently against a stale expectation.
 *  * Every selection is scrolled to before it is asserted or tapped. The dashboard is taller than the
 *    default test window, and a node outside the viewport is not displayed and cannot be tapped --
 *    a tap is delivered at the node's own position. `verticalScroll` on the screen is what makes the
 *    scroll possible, which is also why the two states that draw a single centred child are asserted
 *    without it: neither has a scrollable ancestor to scroll.
 *  * The cards are found by their labels, and on the two clickable ones the label selects the card
 *    itself. `clickable` merges a node's descendants, so a clickable card, its label and its value are
 *    one node: this is what makes tapping the label tap the card's own click.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DashboardScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** One string this screen ships, with a count where it carries one. */
    private fun copy(id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    /** The settled state: a summary that arrived, which is what every card test starts from. */
    private fun state(summary: DashboardSummary? = FakeSystemApi.summary()) =
        DashboardViewModel.UiState(loading = false, summary = summary)

    private fun render(
        state: DashboardViewModel.UiState,
        onOpenReport: (Int) -> Unit = {},
        onOpenReports: () -> Unit = {},
        onOpenLoading: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                DashboardScreen(
                    state = state,
                    onOpenReport = onOpenReport,
                    onOpenReports = onOpenReports,
                    onOpenLoading = onOpenLoading,
                )
            }
        }
    }

    /** One label or value, asserted as drawn after being scrolled to. */
    private fun displayed(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    /** The card whose label is [label], scrolled into view so that a tap on it lands. */
    private fun card(label: String) = composeRule.onNodeWithText(label).performScrollTo()

    @Test
    fun `the five cards and the values the summary carries are drawn`() {
        render(state = state())

        // The five labels, in the web's order and with the web's words.
        for (id in listOf(
            R.string.dashboard_reports_today,
            R.string.dashboard_schedules_tomorrow,
            R.string.dashboard_active_users,
            R.string.dashboard_reports_total,
            R.string.dashboard_schedules_total,
        )) {
            displayed(copy(id))
        }

        // The first card's two lines are the web's own breakdown, and each is built the way the screen
        // builds it: the same resource, with the same count. A copy change, or a breakdown line showing
        // the other module's count, fails here.
        displayed(copy(R.string.dashboard_higiene_count, FakeSystemApi.HIGIENE_DONE))
        displayed(copy(R.string.dashboard_recepcion_count, FakeSystemApi.RECEPCION_DONE))

        // The four plain counts. Every number in the fake's summary is one of its own, so none of these
        // assertions can pass because the screen drew another card's value in its place.
        displayed(FakeSystemApi.SCHEDULES_TOMORROW.toString())
        displayed(FakeSystemApi.ACTIVE_USERS.toString())
        displayed(FakeSystemApi.REPORTS_TOTAL.toString())
        displayed(FakeSystemApi.SCHEDULES_TOTAL.toString())
    }

    /**
     * The web's own rule for the first card, carried over exactly: a summary that names a latest report
     * opens that report, and the same card opens the reports list when it does not. The two are asserted
     * separately because a card wired to only one of them is the failure worth catching, and the
     * assertion on the other callback is what says the choice was made rather than both being called.
     */
    @Test
    fun `the reports card opens the report the summary names`() {
        var openedReportId: Int? = null
        var openedReportsList = 0
        render(
            state = state(),
            onOpenReport = { openedReportId = it },
            onOpenReports = { openedReportsList += 1 },
        )

        card(copy(R.string.dashboard_reports_today)).performClick()

        assertEquals(FakeSystemApi.LATEST_REPORT_ID, openedReportId)
        assertEquals(0, openedReportsList)
    }

    @Test
    fun `the reports card opens the reports list when the summary names no report`() {
        var openedReportId: Int? = null
        var openedReportsList = 0
        render(
            state = state(FakeSystemApi.summary(latestReportId = null)),
            onOpenReport = { openedReportId = it },
            onOpenReports = { openedReportsList += 1 },
        )

        card(copy(R.string.dashboard_reports_today)).performClick()

        assertNull(openedReportId)
        assertEquals(1, openedReportsList)
    }

    @Test
    fun `the schedules card opens the loading surface`() {
        var openedLoading = 0
        render(state = state(), onOpenLoading = { openedLoading += 1 })

        card(copy(R.string.dashboard_schedules_tomorrow)).performClick()

        assertEquals(1, openedLoading)
    }

    /**
     * The loading case of "there is no summary", and the claim is the one this lane was added for: the
     * screen must not draw a zero for a count nobody answered.
     *
     * The state is written out field by field rather than taken from the default constructor, so that what
     * is being rendered is visible in the test: loading, no summary. There is nothing else to set -- the
     * failure is the state that is neither loading nor has a summary, so it has no flag to clear here.
     */
    @Test
    fun `a state with no summary draws no zeroes`() {
        render(state = DashboardViewModel.UiState(loading = true, summary = null))

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        composeRule.onNodeWithText("0").assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.dashboard_higiene_count, 0)).assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.dashboard_recepcion_count, 0)).assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.dashboard_reports_today)).assertDoesNotExist()
    }

    @Test
    fun `a read that did not arrive says so and draws nothing else`() {
        // Not loading and no summary: that *is* the failure state, and it is the whole of it -- nothing
        // sets a flag, because there is none to set.
        render(state = DashboardViewModel.UiState(loading = false, summary = null))

        composeRule.onNodeWithText(copy(R.string.dashboard_unreachable)).assertIsDisplayed()

        // Nothing of a summary, because there is none: this is the state that must not read as a day in
        // which nothing happened, and it offers no retry control -- see the screen for why leaving the
        // tab and coming back is the retry.
        composeRule.onNodeWithText("0").assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.dashboard_reports_today)).assertDoesNotExist()
    }
}
