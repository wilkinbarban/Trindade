package com.trindade.app.reports

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.ui.displayDate
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportsHistoryScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    @Test
    fun `navigation back and filter actions meet 48dp touch targets and invoke callbacks`() {
        var backed = 0
        var cleared = 0
        val state = ReportsHistoryViewModel.UiState(
            date = "2026-09-18",
            month = null,
            loaded = true,
            loading = false,
            items = listOf(FakeReportsApi.historyItem(id = 1)),
        )

        composeRule.setContent {
            TrindadeTheme {
                ReportsHistoryScreen(
                    state = state,
                    onBack = { backed++ },
                    onOpenReport = {},
                    onDateSelected = {},
                    onMonthSelected = {},
                    onClearFilters = { cleared++ },
                    onPrevious = {},
                    onNext = {},
                    onDeactivate = {},
                    onDelete = {},
                )
            }
        }

        val backNode = composeRule.onNodeWithText(copy(R.string.report_back))
        backNode.assertIsDisplayed()
        val backBounds = backNode.getUnclippedBoundsInRoot()
        assertTrue("Back height >= 48dp", backBounds.bottom - backBounds.top >= 48.dp)
        assertTrue("Back width >= 48dp", backBounds.right - backBounds.left >= 48.dp)
        backNode.performClick()
        assertEquals(1, backed)

        val clearNode = composeRule.onNodeWithText(copy(R.string.history_clear_filters))
        clearNode.assertIsDisplayed()
        val clearBounds = clearNode.getUnclippedBoundsInRoot()
        assertTrue("Clear height >= 48dp", clearBounds.bottom - clearBounds.top >= 48.dp)
        assertTrue("Clear width >= 48dp", clearBounds.right - clearBounds.left >= 48.dp)
        clearNode.performClick()
        assertEquals(1, cleared)
    }

    @Test
    fun `pagination buttons and item actions meet 48dp touch targets`() {
        var deactivated = 0
        var deleted = 0
        var previous = 0
        var next = 0

        val state = ReportsHistoryViewModel.UiState(
            page = 2,
            totalPages = 3,
            total = 75,
            loaded = true,
            loading = false,
            items = listOf(FakeReportsApi.historyItem(id = 42)),
        )

        composeRule.setContent {
            TrindadeTheme {
                ReportsHistoryScreen(
                    state = state,
                    onBack = {},
                    onOpenReport = {},
                    onDateSelected = {},
                    onMonthSelected = {},
                    onClearFilters = {},
                    onPrevious = { previous++ },
                    onNext = { next++ },
                    onDeactivate = { deactivated++ },
                    onDelete = { deleted++ },
                )
            }
        }

        val deactNode = composeRule.onNodeWithText(copy(R.string.history_deactivate))
        deactNode.performScrollTo().assertIsDisplayed()
        val deactBounds = deactNode.getUnclippedBoundsInRoot()
        assertTrue("Deactivate height >= 48dp", deactBounds.bottom - deactBounds.top >= 48.dp)
        deactNode.performClick()
        assertEquals(1, deactivated)

        val deleteNode = composeRule.onNodeWithText(copy(R.string.history_delete))
        deleteNode.performScrollTo().assertIsDisplayed()
        val deleteBounds = deleteNode.getUnclippedBoundsInRoot()
        assertTrue("Delete height >= 48dp", deleteBounds.bottom - deleteBounds.top >= 48.dp)

        val prevNode = composeRule.onNodeWithText(copy(R.string.history_previous))
        prevNode.performScrollTo().assertIsDisplayed()
        val prevBounds = prevNode.getUnclippedBoundsInRoot()
        assertTrue("Previous height >= 48dp", prevBounds.bottom - prevBounds.top >= 48.dp)
        prevNode.performClick()
        assertEquals(1, previous)

        val nextNode = composeRule.onNodeWithText(copy(R.string.history_next))
        nextNode.performScrollTo().assertIsDisplayed()
        val nextBounds = nextNode.getUnclippedBoundsInRoot()
        assertTrue("Next height >= 48dp", nextBounds.bottom - nextBounds.top >= 48.dp)
        nextNode.performClick()
        assertEquals(1, next)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun `pagination footer at 320dp width with 1_5x font wraps and stays within screen bounds`() {
        val state = ReportsHistoryViewModel.UiState(
            page = 2,
            totalPages = 3,
            total = 75,
            loaded = true,
            loading = false,
            items = listOf(FakeReportsApi.historyItem(id = 42)),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 1.5f,
                ),
            ) {
                TrindadeTheme {
                    ReportsHistoryScreen(
                        state = state,
                        onBack = {},
                        onOpenReport = {},
                        onDateSelected = {},
                        onMonthSelected = {},
                        onClearFilters = {},
                        onPrevious = {},
                        onNext = {},
                        onDeactivate = {},
                        onDelete = {},
                    )
                }
            }
        }

        val paginationText = copy(R.string.history_pagination, 2, 3, 75)
        val paginationNode = composeRule.onNodeWithText(paginationText).performScrollTo()
        assertWithinViewport(paginationNode)

        val prevNode = composeRule.onNodeWithText(copy(R.string.history_previous)).performScrollTo()
        assertWithinViewport(prevNode)
        val prevBounds = prevNode.getUnclippedBoundsInRoot()
        assertTrue("Previous button height >= 48dp at 1.5x", prevBounds.bottom - prevBounds.top >= 48.dp)
        assertTrue("Previous button width >= 48dp at 1.5x", prevBounds.right - prevBounds.left >= 48.dp)

        val nextNode = composeRule.onNodeWithText(copy(R.string.history_next)).performScrollTo()
        assertWithinViewport(nextNode)
        val nextBounds = nextNode.getUnclippedBoundsInRoot()
        assertTrue("Next button height >= 48dp at 1.5x", nextBounds.bottom - nextBounds.top >= 48.dp)
        assertTrue("Next button width >= 48dp at 1.5x", nextBounds.right - nextBounds.left >= 48.dp)

        assertNoOverlap(prevBounds, nextBounds)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun `pagination footer at 320dp width with 2x font wraps and stays within screen bounds`() {
        val state = ReportsHistoryViewModel.UiState(
            page = 2,
            totalPages = 3,
            total = 75,
            loaded = true,
            loading = false,
            items = listOf(FakeReportsApi.historyItem(id = 42)),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                TrindadeTheme {
                    ReportsHistoryScreen(
                        state = state,
                        onBack = {},
                        onOpenReport = {},
                        onDateSelected = {},
                        onMonthSelected = {},
                        onClearFilters = {},
                        onPrevious = {},
                        onNext = {},
                        onDeactivate = {},
                        onDelete = {},
                    )
                }
            }
        }

        val paginationText = copy(R.string.history_pagination, 2, 3, 75)
        val paginationNode = composeRule.onNodeWithText(paginationText).performScrollTo()
        assertWithinViewport(paginationNode)

        val prevNode = composeRule.onNodeWithText(copy(R.string.history_previous)).performScrollTo()
        assertWithinViewport(prevNode)
        val prevBounds = prevNode.getUnclippedBoundsInRoot()
        assertTrue("Previous button height >= 48dp at 2.0x", prevBounds.bottom - prevBounds.top >= 48.dp)
        assertTrue("Previous button width >= 48dp at 2.0x", prevBounds.right - prevBounds.left >= 48.dp)

        val nextNode = composeRule.onNodeWithText(copy(R.string.history_next)).performScrollTo()
        assertWithinViewport(nextNode)
        val nextBounds = nextNode.getUnclippedBoundsInRoot()
        assertTrue("Next button height >= 48dp at 2.0x", nextBounds.bottom - nextBounds.top >= 48.dp)
        assertTrue("Next button width >= 48dp at 2.0x", nextBounds.right - nextBounds.left >= 48.dp)

        assertNoOverlap(prevBounds, nextBounds)
    }

    @Test
    fun `delete action opens confirmation dialog, cancels safely, and confirms with single dispatch without opening report`() {
        var openedReportId: Int? = null
        var deletedReportId: Int? = null
        var deleteCallCount = 0

        val state = ReportsHistoryViewModel.UiState(
            loaded = true,
            loading = false,
            items = listOf(FakeReportsApi.historyItem(id = 42)),
        )

        composeRule.setContent {
            TrindadeTheme {
                ReportsHistoryScreen(
                    state = state,
                    onBack = {},
                    onOpenReport = { openedReportId = it },
                    onDateSelected = {},
                    onMonthSelected = {},
                    onClearFilters = {},
                    onPrevious = {},
                    onNext = {},
                    onDeactivate = {},
                    onDelete = {
                        deleteCallCount++
                        deletedReportId = it
                    },
                )
            }
        }

        // Tap delete button on the report row
        val rowDeleteNode = composeRule.onNodeWithText(copy(R.string.history_delete))
        rowDeleteNode.performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Confirmation dialog must be displayed; scope assertion to dialog semantics
        val confirmTitle = copy(R.string.history_delete_confirm_title)
        composeRule.onNode(hasAnyAncestor(isDialog()) and hasText(confirmTitle)).assertIsDisplayed()
        assertEquals("Opening delete dialog must not open the report", null, openedReportId)
        assertEquals("Opening delete dialog must not invoke onDelete", 0, deleteCallCount)

        // Cancel the deletion using the dialog's cancel action
        val dialogCancelNode = composeRule.onNode(hasAnyAncestor(isDialog()) and hasText(copy(R.string.loading_cancel)))
        dialogCancelNode.assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Dialog dismissed, onDelete not called, onOpenReport not called
        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertEquals(0, deleteCallCount)
        assertEquals(null, openedReportId)

        // Tap delete button again to reopen dialog
        rowDeleteNode.performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        composeRule.onNode(hasAnyAncestor(isDialog()) and hasText(confirmTitle)).assertIsDisplayed()

        // Click confirm in the dialog, scoped to dialog semantics rather than any loose text node
        val dialogConfirmNode = composeRule.onNode(hasAnyAncestor(isDialog()) and hasText(copy(R.string.history_delete)))
        dialogConfirmNode.assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        // Dialog must be dismissed and onDelete dispatched exactly once with report id 42
        composeRule.onNode(isDialog()).assertDoesNotExist()
        assertEquals("Delete should be dispatched exactly once", 1, deleteCallCount)
        assertEquals(42, deletedReportId)
        assertEquals("Confirming delete must not open report", null, openedReportId)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun `report row actions and details at 320dp width with 1_5x font wrap and stay within bounds`() {
        val item = FakeReportsApi.historyItem(id = 42, reportDate = "2026-09-18", isActive = false)
        val state = ReportsHistoryViewModel.UiState(
            loaded = true,
            loading = false,
            items = listOf(item),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 1.5f,
                ),
            ) {
                TrindadeTheme {
                    ReportsHistoryScreen(
                        state = state,
                        onBack = {},
                        onOpenReport = {},
                        onDateSelected = {},
                        onMonthSelected = {},
                        onClearFilters = {},
                        onPrevious = {},
                        onNext = {},
                        onDeactivate = {},
                        onDelete = {},
                    )
                }
            }
        }

        // Assert identity texts are visible and rendered without clipping
        val dateNode = composeRule.onNodeWithText(displayDate("2026-09-18")).performScrollTo()
        assertWithinViewport(dateNode)

        val turnoNode = composeRule.onNodeWithText(copy(R.string.history_turno_tarde)).performScrollTo()
        assertWithinViewport(turnoNode)

        val inactiveNode = composeRule.onNodeWithText(copy(R.string.history_inactive)).performScrollTo()
        assertWithinViewport(inactiveNode)

        val userNode = composeRule.onNodeWithText("admin").performScrollTo()
        assertWithinViewport(userNode)

        // Assert actions meet touch target size and do not overlap
        val deactNode = composeRule.onNodeWithText(copy(R.string.history_deactivate)).performScrollTo()
        assertWithinViewport(deactNode)
        val deactBounds = deactNode.getUnclippedBoundsInRoot()
        assertTrue("Deactivate height >= 48dp at 1.5x", deactBounds.bottom - deactBounds.top >= 48.dp)
        assertTrue("Deactivate width >= 48dp at 1.5x", deactBounds.right - deactBounds.left >= 48.dp)

        val deleteNode = composeRule.onNodeWithText(copy(R.string.history_delete)).performScrollTo()
        assertWithinViewport(deleteNode)
        val deleteBounds = deleteNode.getUnclippedBoundsInRoot()
        assertTrue("Delete height >= 48dp at 1.5x", deleteBounds.bottom - deleteBounds.top >= 48.dp)
        assertTrue("Delete width >= 48dp at 1.5x", deleteBounds.right - deleteBounds.left >= 48.dp)

        assertNoOverlap(deactBounds, deleteBounds)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun `report row actions and details at 320dp width with 2x font wrap and stay within bounds`() {
        val item = FakeReportsApi.historyItem(id = 42, reportDate = "2026-09-18", isActive = false)
        val state = ReportsHistoryViewModel.UiState(
            loaded = true,
            loading = false,
            items = listOf(item),
        )

        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                TrindadeTheme {
                    ReportsHistoryScreen(
                        state = state,
                        onBack = {},
                        onOpenReport = {},
                        onDateSelected = {},
                        onMonthSelected = {},
                        onClearFilters = {},
                        onPrevious = {},
                        onNext = {},
                        onDeactivate = {},
                        onDelete = {},
                    )
                }
            }
        }

        // Assert identity texts are visible and rendered without clipping
        val dateNode = composeRule.onNodeWithText(displayDate("2026-09-18")).performScrollTo()
        assertWithinViewport(dateNode)

        val turnoNode = composeRule.onNodeWithText(copy(R.string.history_turno_tarde)).performScrollTo()
        assertWithinViewport(turnoNode)

        val inactiveNode = composeRule.onNodeWithText(copy(R.string.history_inactive)).performScrollTo()
        assertWithinViewport(inactiveNode)

        val userNode = composeRule.onNodeWithText("admin").performScrollTo()
        assertWithinViewport(userNode)

        // Assert actions meet touch target size and do not overlap
        val deactNode = composeRule.onNodeWithText(copy(R.string.history_deactivate)).performScrollTo()
        assertWithinViewport(deactNode)
        val deactBounds = deactNode.getUnclippedBoundsInRoot()
        assertTrue("Deactivate height >= 48dp at 2.0x", deactBounds.bottom - deactBounds.top >= 48.dp)
        assertTrue("Deactivate width >= 48dp at 2.0x", deactBounds.right - deactBounds.left >= 48.dp)

        val deleteNode = composeRule.onNodeWithText(copy(R.string.history_delete)).performScrollTo()
        assertWithinViewport(deleteNode)
        val deleteBounds = deleteNode.getUnclippedBoundsInRoot()
        assertTrue("Delete height >= 48dp at 2.0x", deleteBounds.bottom - deleteBounds.top >= 48.dp)
        assertTrue("Delete width >= 48dp at 2.0x", deleteBounds.right - deleteBounds.left >= 48.dp)

        assertNoOverlap(deactBounds, deleteBounds)
    }

    private fun assertWithinViewport(
        node: SemanticsNodeInteraction,
        viewportWidth: Dp = 320.dp,
    ) {
        node.assertIsDisplayed()
        val unclipped = node.getUnclippedBoundsInRoot()
        val clipped = node.getBoundsInRoot()
        assertTrue("Left bound (${unclipped.left}) must be >= 0dp", unclipped.left >= 0.dp)
        assertTrue("Right bound (${unclipped.right}) must be <= $viewportWidth", unclipped.right <= viewportWidth)
        assertEquals("Left edge clipped vs unclipped mismatch", unclipped.left, clipped.left)
        assertEquals("Right edge clipped vs unclipped mismatch", unclipped.right, clipped.right)
    }

    private fun assertNoOverlap(a: DpRect, b: DpRect) {
        val overlaps = a.left < b.right && a.right > b.left && a.top < b.bottom && a.bottom > b.top
        assertFalse("Bounding boxes must not overlap: a=$a, b=$b", overlaps)
    }
}
