package com.trindade.app.loading

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.DriversResponseDriversInner
import com.trindade.app.contract.models.SchedulesResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.network.LoadingApi
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import retrofit2.Response

/**
 * The loading grid, rendered rather than read, for the two things only this lane can see: which of its rows
 * offers the way into the edit screen, and where the row an edit moved comes back drawn.
 *
 * **The way in.** That first rule is a decision the view model cannot be made to answer for the grid: `LoadingViewModel` says what the day holds and nothing about what a row may be edited by,
 * because the two flags that answer it live on the row the server sent. What can go wrong here is not
 * whether the flags are read at all -- `isReadOnly()` is one function and the edit screen reads it too --
 * but whether this screen reads *that* function: a second copy of the expression, written beside it,
 * drifts the moment the server sends a pair the copy does not handle, and the operator is then offered a
 * way into a screen that draws no save, which is the contradiction the predicate exists to prevent.
 *
 * So the day below is built to disagree with itself in the two shapes the wire allows, and the count of
 * way-in actions is the assertion that carries the weight: one row is left open and three are closed, one
 * of them by each flag alone and one by a pair that contradicts itself, so any copy of the predicate that
 * forgets half of it offers a second way in and fails here.
 *
 * **Where the row comes back.** The second rule is the one this unit's own review found. The edit surface
 * reports a save by clearing the overlay flag, the grid is drawn again on the day it was already showing,
 * and that arrival used to be answered from the read that ran before the write -- so the row the operator
 * had just moved was drawn back in the slot they moved it out of, which reads as their own edit having
 * done nothing at all. What can go wrong with it is not the rule itself, which is the view model's and is
 * asserted in its own lane, but the wiring: `stale` is the fact `MainActivity` records where the editor's
 * save is reported, and this route is the only thing that turns that fact into a read. A route that
 * stopped passing it on would leave the view model's leg passing and the operator's row behind, which is
 * why the leg below renders the route itself rather than handing a state to the screen.
 *
 * Two things about this file are deliberate, and both are the lane's conventions:
 *
 *  * `GraphicsMode.NATIVE` makes the rendering real -- text is measured by the framework's layout rather
 *    than by a shadow that returns a constant -- and every claim below is about a node being displayed or
 *    absent, which a node's size is what "displayed" turns on.
 *  * Every selection is scrolled to before it is asserted or tapped, because the grid is a `LazyColumn`
 *    that scrolls itself and a tap is delivered at the node's own position. The day is small enough to
 *    compose inside the 1000dp-tall window, which is what keeps every row selectable; a longer day would
 *    need a different selection strategy, not a different assertion. The one selection that is not
 *    scrolled to is the route's own leg below, which measures the row's bounds against the headings':
 *    its day is three slots with one row, the whole of it is inside that window, and scrolling would
 *    move the very bounds it compares.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** One string this screen draws, looked up rather than typed. */
    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ---- The day the screen is handed ----

    /**
     * One row of the day, carrying the two permission flags the server computes together.
     *
     * `entry` gives every row its own driver name, so each row's identity line is distinct and an
     * assertion about one row cannot be satisfied by another's.
     */
    private fun row(
        id: Int,
        slot: String,
        canEdit: Boolean = true,
        readOnly: Boolean = false,
    ): SchedulesResponseSchedulesInner =
        FakeLoadingApi.entry(id = id, slot = slot).copy(canEdit = canEdit, readOnly = readOnly)

    /** The window has closed: `readOnly` said so and nothing was positively marked editable. */
    private val closedWindow = row(id = CLOSED_WINDOW_ID, slot = "04:00", canEdit = false, readOnly = true)

    /**
     * The pair that contradicts itself, with the refusal winning.
     *
     * The server computes both flags from one call and answers `readOnly` exactly as `!canEdit`, so this
     * shape is one only the wire's typing allows -- and it is the shape a predicate reading `canEdit` alone
     * would call editable. The predicate states that the explicit refusal wins.
     */
    private val readOnlyStated = row(id = READ_ONLY_STATED_ID, slot = "04:30", canEdit = true, readOnly = true)

    /** The pair that contradicts itself the other way: nothing was marked editable. */
    private val nothingMarked = row(id = NOTHING_MARKED_ID, slot = "06:00", canEdit = false, readOnly = false)

    /** The one row the server left open, and the day's positive control. */
    private val editable = row(id = EDITABLE_ID, slot = "06:30").copy(
        driverName = "Carlos Lima",
        vehicleDescription = "Caminhão Toco",
        vehiclePlate = "TRN-9020",
    )

    private val day = listOf(closedWindow, readOnlyStated, nothingMarked, editable)

    /**
     * The grid's state: a settled read of one day.
     *
     * The configured slots are exactly the slots the rows sit in, so the grid draws four blocks and no
     * empty one, and every row below is on screen.
     */
    private fun state() = LoadingViewModel.UiState(
        date = DATE,
        loading = false,
        schedules = day,
        timeSlots = SLOTS,
    )

    private val responsiveEntry = editable.copy(timeSlot = "04:00")

    private fun responsiveState() = LoadingViewModel.UiState(
        date = DATE,
        loading = false,
        schedules = listOf(responsiveEntry),
        timeSlots = listOf("04:00"),
    )

    /**
     * The grid with one slot's add form open, which is where the two selections live.
     *
     * The selected driver is the company one, so the vehicle list is drawn as well: a company driver
     * brings no vehicle of their own and the server refuses the assignment without one.
     */
    private fun addingState() = LoadingViewModel.UiState(
        date = DATE,
        loading = false,
        schedules = listOf(editable.copy(timeSlot = "04:00")),
        timeSlots = listOf("04:00"),
        drivers = FakeLoadingApi.defaultDrivers(),
        vehicles = FakeLoadingApi.defaultVehicles(),
        addingToSlot = "04:00",
        selectedDriverId = FakeLoadingApi.CASA_DRIVER_ID,
    )

    private fun renderAddingForm(
        onDriverSelected: (Int) -> Unit = {},
        onVehicleSelected: (Int) -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = addingState(),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = onDriverSelected,
                    onVehicleSelected = onVehicleSelected,
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                )
            }
        }
    }

    /** One state of the grid, drawn as given: the date-scoped rule is the view model's and is asserted there. */
    private fun renderState(state: LoadingViewModel.UiState) {
        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state,
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                )
            }
        }
    }

    // ---- The two selections of the add form (AND-03) ----

    /**
     * The rule this pair of tests exists for: the whole row is the control.
     *
     * The trap the change closes is small and real. The radio alone used to own the click, so the row an
     * operator reads -- the name of the driver, the description and plate of the vehicle -- was a label
     * they could touch with nothing happening, while a circle a few points wide did the selecting. The
     * label is what they aim at, which is why the tap below goes to the label and not to the button.
     */
    @Test
    fun `the whole driver row is the control and the label selects that driver`() {
        val selected = mutableListOf<Int>()
        renderAddingForm(onDriverSelected = { selected += it })

        composeRule.onNodeWithText("Fletero").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(listOf(FakeLoadingApi.FLETERO_DRIVER_ID), selected)
    }

    @Test
    fun `a driver row carries the radio role and its own selected state`() {
        renderAddingForm()

        val chosen = composeRule.onNodeWithText("Carlos").performScrollTo()
        chosen.assertIsDisplayed()
        chosen.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
        chosen.assertIsSelected()

        composeRule.onNodeWithText("Fletero").assertIsNotSelected()
    }

    @Test
    fun `the whole vehicle row is the control too`() {
        val selected = mutableListOf<Int>()
        renderAddingForm(onVehicleSelected = { selected += it })

        composeRule.onNodeWithText("Fiorino · XYZ9876").performScrollTo().assertIsDisplayed().performClick()

        assertEquals(listOf(FakeLoadingApi.VEHICLE_ID), selected)
    }

    // ---- A day that is still being read offers nothing to change (AND-01) ----

    /**
     * The other half of the date-scoped rule, on the screen's side.
     *
     * Clearing the day's rows belongs to the view model, and this leg does not re-assert it: what is
     * asserted here is that the actions the grid still draws while a read is in the air -- the way into a
     * new entry and the removal of a row already on screen -- cannot be spent on data whose day has not
     * been confirmed. A grid that cleared its rows but left the buttons live would offer a form for a slot
     * of a day nobody has read yet.
     */
    @Test
    fun `while the day is being read the grid offers no mutation`() {
        renderState(state().copy(loading = true))

        // One way into a new entry per configured slot, and not one of them is live while the day is
        // unread: the count is asserted first so a grid that drew no slots at all cannot pass by absence.
        val waysIn = composeRule.onAllNodesWithText(copy(R.string.loading_add_entry))
        waysIn.assertCountEquals(SLOTS.size)
        repeat(SLOTS.size) { index -> waysIn[index].assertIsNotEnabled() }

        // And the removal of every row the settled day drew is withheld too.
        val removals = composeRule.onAllNodesWithText(copy(R.string.report_photo_remove))
        removals.assertCountEquals(day.size)
        repeat(day.size) { index -> removals[index].assertIsNotEnabled() }
    }

    // ---- Rendering ----

    /** What the grid reported, in the order it reported it: the row's id and the day it sits on. */
    private val opened = mutableListOf<Pair<Int, String>>()

    private fun render() {
        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state(),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { id, date -> opened += id to date },
                    onLoadExport = {},
                )
            }
        }
    }

    /** One label, asserted as drawn after being scrolled to. */
    private fun displayed(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    // ---- The tests ----

    /**
     * The rule this file exists for: the row the server left open offers the way in, and the three the
     * server closed draw none.
     *
     * The count is the assertion that refuses a copy of the predicate. Every row offers the same label for
     * the way in -- it is one word, the web's own `loading.editEntry` -- so the number of nodes carrying
     * it is the number of rows that offer one, and exactly one may. The day below is built so that a
     * second copy of the expression is caught by that count: one closed row states `readOnly` beside
     * `canEdit`, which a screen reading `canEdit == true` alone would call editable, and another has
     * neither flag set, which a screen reading `readOnly == false` alone would. Either copy draws a second
     * way in, and this fails.
     *
     * The four rows are asserted whole before the tap, so what the count above is read against is a grid
     * that drew its day: each row's own identity line is displayed, and the day's four delete actions are
     * counted. A grid that drew nothing would fail here rather than pass the count that matters.
     *
     * The tap then goes to the one way in, and the assertion is on what it reported. The label's node is
     * unique on this screen precisely because of the count above -- that is what makes the tap unambiguous
     * -- and which row offers it is settled by the id it carries: an action drawn on the wrong row reports
     * that row's id. The day travels with it because the editor reads the row out of its day, and the day
     * is the grid's own state rather than anything a caller passed in.
     */
    @Test
    fun `the row the server left editable offers the way in, and the three it closed draw none`() {
        render()

        composeRule.onAllNodesWithText(copy(R.string.report_edit)).assertCountEquals(1)

        day.forEach { displayed(describe(it)) }
        composeRule.onAllNodesWithText(copy(R.string.report_photo_remove)).assertCountEquals(day.size)

        composeRule.onNodeWithText(copy(R.string.report_edit)).performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(EDITABLE_ID to DATE), opened)
    }

    @Test
    fun `navigation back, history, edit, and delete meet 48dp touch targets`() {
        var backed = 0
        var openedHistory = 0
        var deletedId = 0
        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state(),
                    onBack = { backed++ },
                    onOpenHistory = { openedHistory++ },
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = { deletedId = it },
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
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

        val historyNode = composeRule.onNodeWithText(copy(R.string.report_history))
        historyNode.assertIsDisplayed()
        val historyBounds = historyNode.getUnclippedBoundsInRoot()
        assertTrue("History height >= 48dp", historyBounds.bottom - historyBounds.top >= 48.dp)
        assertTrue("History width >= 48dp", historyBounds.right - historyBounds.left >= 48.dp)
        historyNode.performClick()
        assertEquals(1, openedHistory)

        val editNode = composeRule.onNodeWithText(copy(R.string.report_edit))
        editNode.performScrollTo().assertIsDisplayed()
        val editBounds = editNode.getUnclippedBoundsInRoot()
        assertTrue("Edit height >= 48dp", editBounds.bottom - editBounds.top >= 48.dp)
        assertTrue("Edit width >= 48dp", editBounds.right - editBounds.left >= 48.dp)

        val deleteNodes = composeRule.onAllNodesWithText(copy(R.string.report_photo_remove))
        val firstDeleteNode = deleteNodes[0].performScrollTo().assertIsDisplayed()
        val firstDeleteBounds = firstDeleteNode.getUnclippedBoundsInRoot()
        assertTrue("Delete height >= 48dp", firstDeleteBounds.bottom - firstDeleteBounds.top >= 48.dp)
        assertTrue("Delete width >= 48dp", firstDeleteBounds.right - firstDeleteBounds.left >= 48.dp)
        firstDeleteNode.performClick()
        assertEquals(CLOSED_WINDOW_ID, deletedId)
    }

    @Test
    fun `entry edit and remove actions are isolated and do not trigger each other`() {
        var editedId: Int? = null
        var editedDate: String? = null
        var editCount = 0
        var deletedId: Int? = null
        var deleteCount = 0

        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state(),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {
                        deleteCount++
                        deletedId = it
                    },
                    onEditEntry = { id, date ->
                        editCount++
                        editedId = id
                        editedDate = date
                    },
                    onLoadExport = {},
                )
            }
        }

        // Tap Remove on the same editable row that also has Edit (editable entry, EDITABLE_ID)
        composeRule.onNodeWithText(describe(editable)).performScrollTo().assertIsDisplayed()
        val removeButtons = composeRule.onAllNodesWithText(copy(R.string.report_photo_remove))
        val editableRemoveNode = removeButtons[3].performScrollTo().assertIsDisplayed()
        editableRemoveNode.performClick()
        composeRule.waitForIdle()

        assertEquals("Delete callback must be called exactly once for editable row", 1, deleteCount)
        assertEquals(EDITABLE_ID, deletedId)
        assertEquals("Edit callback must NOT be dispatched when clicking remove on editable row", 0, editCount)
        assertEquals(null, editedId)

        // Now tap Edit on that same editable entry
        val editNode = composeRule.onNodeWithText(copy(R.string.report_edit)).performScrollTo().assertIsDisplayed()
        editNode.performClick()
        composeRule.waitForIdle()

        assertEquals("Edit callback must be called exactly once", 1, editCount)
        assertEquals(EDITABLE_ID, editedId)
        assertEquals(DATE, editedDate)
        assertEquals("Delete callback count must remain 1 (no cross-dispatch on edit)", 1, deleteCount)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun `loading screen header and entry actions at 320dp with 1_5x font wrap and stay within bounds`() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 1.5f,
                ),
            ) {
                TrindadeTheme {
                    LoadingScreen(
                        state = responsiveState(),
                        onBack = {},
                        onOpenHistory = {},
                        onStartAdding = {},
                        onCancelAdding = {},
                        onDriverSelected = {},
                        onVehicleSelected = {},
                        onConfirmAdd = {},
                        onDelete = {},
                        onEditEntry = { _, _ -> },
                        onLoadExport = {},
                    )
                }
            }
        }

        // Header title and navigation action button
        val titleNode = composeRule.onNodeWithText(copy(R.string.loading_title)).performScrollTo()
        assertWithinViewport(titleNode)

        val historyNode = composeRule.onNodeWithText(copy(R.string.report_history)).performScrollTo()
        assertWithinViewport(historyNode)
        val historyBounds = historyNode.getUnclippedBoundsInRoot()
        assertTrue("History button height >= 48dp at 1.5x", historyBounds.bottom - historyBounds.top >= 48.dp)
        assertTrue("History button width >= 48dp at 1.5x", historyBounds.right - historyBounds.left >= 48.dp)

        // Export action
        val exportNode = composeRule.onNodeWithText(copy(R.string.export_load)).performScrollTo()
        assertWithinViewport(exportNode)
        val exportBounds = exportNode.getUnclippedBoundsInRoot()
        assertTrue("Export button height >= 48dp at 1.5x", exportBounds.bottom - exportBounds.top >= 48.dp)

        // Slot heading and fletero counter
        val slotHeadingNode = composeRule.onNodeWithText("04:00").performScrollTo()
        assertWithinViewport(slotHeadingNode)

        val counterText = copy(R.string.loading_fleteros, 1, FleteroQuota.LIMIT)
        val counterNode = composeRule.onNodeWithText(counterText).performScrollTo()
        assertWithinViewport(counterNode)

        // Entry identity text (driver, vehicle, plate)
        val identityNode = composeRule.onNodeWithText(describe(responsiveEntry)).performScrollTo()
        assertWithinViewport(identityNode)
        val identityBounds = identityNode.getUnclippedBoundsInRoot()

        // Edit button on editable row
        val editNode = composeRule.onNodeWithText(copy(R.string.report_edit)).performScrollTo()
        assertWithinViewport(editNode)
        val editBounds = editNode.getUnclippedBoundsInRoot()
        assertTrue("Edit button height >= 48dp at 1.5x", editBounds.bottom - editBounds.top >= 48.dp)
        assertTrue("Edit button width >= 48dp at 1.5x", editBounds.right - editBounds.left >= 48.dp)

        // Remove button on editable row
        val removeNode = composeRule.onNodeWithText(copy(R.string.report_photo_remove)).performScrollTo()
        assertWithinViewport(removeNode)
        val removeBounds = removeNode.getUnclippedBoundsInRoot()
        assertTrue("Remove button height >= 48dp at 1.5x", removeBounds.bottom - removeBounds.top >= 48.dp)
        assertTrue("Remove button width >= 48dp at 1.5x", removeBounds.right - removeBounds.left >= 48.dp)

        // Assert no overlap
        assertNoOverlap(editBounds, removeBounds)
        assertNoOverlap(identityBounds, editBounds)
        assertNoOverlap(identityBounds, removeBounds)
        assertTrue("Identity text sits above edit action", identityBounds.bottom <= editBounds.top)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun `loading screen header and entry actions at 320dp with 2x font wrap and stay within bounds`() {
        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                TrindadeTheme {
                    LoadingScreen(
                        state = responsiveState(),
                        onBack = {},
                        onOpenHistory = {},
                        onStartAdding = {},
                        onCancelAdding = {},
                        onDriverSelected = {},
                        onVehicleSelected = {},
                        onConfirmAdd = {},
                        onDelete = {},
                        onEditEntry = { _, _ -> },
                        onLoadExport = {},
                    )
                }
            }
        }

        // Header title and navigation action button
        val titleNode = composeRule.onNodeWithText(copy(R.string.loading_title)).performScrollTo()
        assertWithinViewport(titleNode)

        val historyNode = composeRule.onNodeWithText(copy(R.string.report_history)).performScrollTo()
        assertWithinViewport(historyNode)
        val historyBounds = historyNode.getUnclippedBoundsInRoot()
        assertTrue("History button height >= 48dp at 2.0x", historyBounds.bottom - historyBounds.top >= 48.dp)
        assertTrue("History button width >= 48dp at 2.0x", historyBounds.right - historyBounds.left >= 48.dp)

        // Export action
        val exportNode = composeRule.onNodeWithText(copy(R.string.export_load)).performScrollTo()
        assertWithinViewport(exportNode)
        val exportBounds = exportNode.getUnclippedBoundsInRoot()
        assertTrue("Export button height >= 48dp at 2.0x", exportBounds.bottom - exportBounds.top >= 48.dp)

        // Slot heading and fletero counter
        val slotHeadingNode = composeRule.onNodeWithText("04:00").performScrollTo()
        assertWithinViewport(slotHeadingNode)

        val counterText = copy(R.string.loading_fleteros, 1, FleteroQuota.LIMIT)
        val counterNode = composeRule.onNodeWithText(counterText).performScrollTo()
        assertWithinViewport(counterNode)

        // Entry identity text (driver, vehicle, plate)
        val identityNode = composeRule.onNodeWithText(describe(responsiveEntry)).performScrollTo()
        assertWithinViewport(identityNode)
        val identityBounds = identityNode.getUnclippedBoundsInRoot()

        // Edit button on editable row
        val editNode = composeRule.onNodeWithText(copy(R.string.report_edit)).performScrollTo()
        assertWithinViewport(editNode)
        val editBounds = editNode.getUnclippedBoundsInRoot()
        assertTrue("Edit button height >= 48dp at 2.0x", editBounds.bottom - editBounds.top >= 48.dp)
        assertTrue("Edit button width >= 48dp at 2.0x", editBounds.right - editBounds.left >= 48.dp)

        // Remove button on editable row
        val removeNode = composeRule.onNodeWithText(copy(R.string.report_photo_remove)).performScrollTo()
        assertWithinViewport(removeNode)
        val removeBounds = removeNode.getUnclippedBoundsInRoot()
        assertTrue("Remove button height >= 48dp at 2.0x", removeBounds.bottom - removeBounds.top >= 48.dp)
        assertTrue("Remove button width >= 48dp at 2.0x", removeBounds.right - removeBounds.left >= 48.dp)

        // Assert no overlap
        assertNoOverlap(editBounds, removeBounds)
        assertNoOverlap(identityBounds, editBounds)
        assertNoOverlap(identityBounds, removeBounds)
        assertTrue("Identity text sits above edit action", identityBounds.bottom <= editBounds.top)
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

    // ---- The other rule: where the row an edit moved comes back drawn ----

    /**
     * The day's rows as the server has them, with the moved row in a different slot on the read after the
     * first.
     *
     * The fake this unit already has answers one fixed day, and the claim here is about a *re-read*: the
     * grid has to be shown drawing the day the server now has rather than the copy it was holding, which is
     * a claim no fixed answer can carry. Everything else is delegated, so the reads this leg is not about
     * stay the fake's own.
     */
    private class DayThatMoves(
        private val movesFrom: String,
        private val movesTo: String,
        private val rowId: Int,
        private val failsOnRead: Int? = null,
    ) : LoadingApi by FakeLoadingApi() {

        /** The dates asked for, in order: what an ordinary arrival must not lengthen. */
        val asked = mutableListOf<String>()
        private var successfulReads = 0

        override suspend fun schedules(date: String): Response<SchedulesResponse> {
            asked += date
            if (asked.size == failsOnRead) return Response.error(500, FakeLoadingApi.EMPTY_BODY)

            val slot = if (successfulReads++ == 0) movesFrom else movesTo
            return Response.success(
                SchedulesResponse(schedules = listOf(FakeLoadingApi.entry(rowId, slot))),
            )
        }
    }

    /**
     * The rule this unit was finished with, rendered: after the edit surface reports a save, the grid draws
     * the row it changed in the slot it was moved to, without the operator doing anything else.
     *
     * The route is the composable under test rather than the screen, because the arrival is the route's --
     * it is the only place the view model is read from -- and because the fact the save leaves behind comes
     * in as one of its parameters. Rendering it is what shows the wiring: the same arrival whose read the
     * view model's own lane asserts, called by the same route the app draws, with the fact `MainActivity`
     * records where the editor reports its save.
     *
     * The arrival after the editor closes is a composition of this route of its own -- the editor replaces
     * the grid in `MainActivity`'s branch, so the grid that comes back is a fresh one -- and the key below is
     * that remount rather than a second, invented door. The second direction is here for the reason the view
     * model's leg gives: the ordinary arrival must still be answered from the read the constructor started,
     * so a grid that reloaded on every arrival fails this leg even though it would make the stale one pass.
     */
    @Test
    fun `a failed stale arrival keeps the fact and the next arrival draws the moved row`() {
        val api = DayThatMoves(
            movesFrom = SLOT_MOVED_FROM,
            movesTo = SLOT_MOVED_TO,
            rowId = MOVED_ROW_ID,
            failsOnRead = 2,
        )
        val model = LoadingViewModel(LoadingRepository(api))
        val today = saoPauloToday()
        val row = FakeLoadingApi.entry(MOVED_ROW_ID, SLOT_MOVED_FROM)

        // The two inputs `MainActivity` supplies: the save report the editor leaves behind, and the way the
        // grid hands that fact back once it has acted on it. The count proves success acknowledges once.
        val stale = mutableStateOf(false)
        val arrival = mutableStateOf(0)
        var staleReads = 0

        composeRule.setContent {
            TrindadeTheme {
                key(arrival.value) {
                    LoadingRoute(
                        onBack = {},
                        onOpenHistory = {},
                        onEditEntry = { _, _ -> },
                        stale = stale.value,
                        onStaleRead = {
                            staleReads++
                            stale.value = false
                        },
                        viewModel = model,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // One read, and the pre-edit row: this arrival is the loading tab's ordinary constructor-backed
        // arrival. Asking again here would already violate the no-double-read side of the contract.
        assertEquals(listOf(today), api.asked)
        assertEquals(SLOT_MOVED_FROM, drawnSlot(row))

        // The first stale arrival reaches the server, but its schedules response fails. The route must not
        // spend MainActivity's one-shot; there is no truthful replacement to acknowledge yet.
        stale.value = true
        arrival.value++
        composeRule.waitForIdle()

        assertEquals(listOf(today, today), api.asked)
        assertEquals(0, staleReads)
        assertEquals(true, stale.value)

        // MainActivity still owns the fact, so the next remount retries. This answer succeeds, writes the
        // moved row, and only then acknowledges exactly once.
        arrival.value++
        composeRule.waitForIdle()

        assertEquals(listOf(today, today, today), api.asked)
        assertEquals(1, staleReads)
        assertFalse("the successful retry takes the fact", stale.value)
        assertEquals(SLOT_MOVED_TO, drawnSlot(row))

        // The subsequent ordinary arrival keeps the established no-double-read behavior and cannot
        // acknowledge the already-consumed fact again.
        arrival.value++
        composeRule.waitForIdle()

        assertEquals(listOf(today, today, today), api.asked)
        assertEquals(1, staleReads)
        assertEquals(SLOT_MOVED_TO, drawnSlot(row))
    }

    /**
     * The slot whose block [row] is drawn in, and the count that refuses it being drawn in two.
     *
     * The row's identity and the slot headings are all plain text, so a query on either alone cannot say
     * which block the row is inside; the bounds can. The grid draws every configured slot in the server's
     * order, so a row's block is the last one whose heading is above it.
     */
    private fun drawnSlot(row: SchedulesResponseSchedulesInner): String {
        composeRule.onAllNodesWithText(describe(row)).assertCountEquals(1)
        val identity = composeRule.onNodeWithText(describe(row)).getUnclippedBoundsInRoot()

        return MOVED_SLOTS.last { slot ->
            composeRule.onNodeWithText(slot).getUnclippedBoundsInRoot().top <= identity.top
        }
    }

    // ---- Export actions ----

    private class TestClipboardManager : ClipboardManager {
        private var clip: AnnotatedString? = null
        override fun getText(): AnnotatedString? = clip
        override fun setText(annotatedString: AnnotatedString) {
            clip = annotatedString
        }
        override fun hasText(): Boolean = clip != null
    }

    @Test
    fun `does not offer export actions until export text is available`() {
        render()

        composeRule.onNodeWithText(copy(R.string.export_load)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.export_copy)).assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.export_share_text)).assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.export_share_pdf)).assertDoesNotExist()
    }

    @Test
    fun `the export dialog offers the one-on-one chat for the day's fleteros that have a number`() {
        val opened = mutableListOf<Int>()
        val withNumber = DriversResponseDriversInner(
            id = 7, name = "Ana", licensePlate = null, phone = "+55 (11) 98888-7777",
            driverType = DriversResponseDriversInner.DriverType.fletero,
        )
        val withoutNumber = DriversResponseDriversInner(
            id = 8, name = "Beto", licensePlate = null, phone = null,
            driverType = DriversResponseDriversInner.DriverType.fletero,
        )

        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state().copy(
                        exportText = "CRONOGRAMA DE CARREGAMENTO",
                        schedules = listOf(FakeLoadingApi.entry(7, "04:00"), FakeLoadingApi.entry(8, "05:00")),
                        drivers = listOf(withNumber, withoutNumber),
                    ),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                    onShareText = {},
                    onSharePdf = {},
                    onOpenWhatsApp = { opened += it.id },
                )
            }
        }

        // Offered only for the fletero the chat can actually reach, and the label names the person
        // rather than the action, because which driver a message goes to is the choice being made here.
        composeRule.onNodeWithText(copy(R.string.export_send_to_fletero)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("Ana").performScrollTo().assertIsDisplayed().performClick()
        composeRule.onNodeWithText("Beto").assertDoesNotExist()
        composeRule.waitForIdle()
        assertEquals(listOf(7), opened)
    }

    /**
     * The overlap the operator saw: two fleteros in one day drew one button on top of the other.
     *
     * The names come from the roster rather than from a fixed list, so a column of buttons borrowed each
     * line from the one above it as soon as a second driver had a number. What the assertion reads is the
     * placement itself -- the second button starts either after the first one ends on the same line, or below
     * it -- because presence alone would pass for a screen that drew both in the same place, which is exactly
     * the layout that was reported.
     */
    @Test
    fun `two fleteros with numbers get their own space in the dialog`() {
        val ana = DriversResponseDriversInner(
            id = 7, name = "Ana", licensePlate = null, phone = "+55 (11) 98888-7777",
            driverType = DriversResponseDriversInner.DriverType.fletero,
        )
        val beto = DriversResponseDriversInner(
            id = 8, name = "Beto", licensePlate = null, phone = "+55 (11) 97777-6666",
            driverType = DriversResponseDriversInner.DriverType.fletero,
        )

        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state().copy(
                        exportText = "CRONOGRAMA DE CARREGAMENTO",
                        schedules = listOf(FakeLoadingApi.entry(7, "04:00"), FakeLoadingApi.entry(8, "05:00")),
                        drivers = listOf(ana, beto),
                    ),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                    onShareText = {},
                    onSharePdf = {},
                    onOpenWhatsApp = {},
                )
            }
        }

        val first = composeRule.onNodeWithText("Ana").performScrollTo().getUnclippedBoundsInRoot()
        val second = composeRule.onNodeWithText("Beto").performScrollTo().getUnclippedBoundsInRoot()

        val gapOnOneLine = second.left >= first.right
        val lineOfItsOwn = second.top >= first.bottom
        assertTrue(
            "Ana [$first] and Beto [$second] overlap: neither a gap on one line nor a line of their own",
            gapOnOneLine || lineOfItsOwn,
        )
    }

    @Test
    fun `offers export actions when export text is available`() {
        val sampleText = "CRONOGRAMA DE CARREGAMENTO - 15/01/2025"
        var shareTextCalls = 0
        var sharePdfCalls = 0

        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state().copy(exportText = sampleText),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                    onShareText = { shareTextCalls++ },
                    onSharePdf = { sharePdfCalls++ },
                )
            }
        }

        composeRule.onNodeWithText(copy(R.string.export_copy)).performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.export_share_text)).performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, shareTextCalls)
        assertEquals(0, sharePdfCalls)

        composeRule.onNodeWithText(copy(R.string.export_share_pdf)).performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()
        assertEquals(1, shareTextCalls)
        assertEquals(1, sharePdfCalls)
    }

    @Test
    fun `export copy action writes exact text to clipboard`() {
        val sampleText = "CRONOGRAMA DE CARREGAMENTO - 15/01/2025"
        val testClipboard = TestClipboardManager()

        composeRule.setContent {
            CompositionLocalProvider(
                LocalClipboardManager provides testClipboard,
            ) {
                TrindadeTheme {
                    LoadingScreen(
                        state = state().copy(exportText = sampleText),
                        onBack = {},
                        onOpenHistory = {},
                        onStartAdding = {},
                        onCancelAdding = {},
                        onDriverSelected = {},
                        onVehicleSelected = {},
                        onConfirmAdd = {},
                        onDelete = {},
                        onEditEntry = { _, _ -> },
                        onLoadExport = {},
                    )
                }
            }
        }

        composeRule.onNodeWithText(copy(R.string.export_copy)).performScrollTo().assertIsDisplayed().performClick()
        composeRule.waitForIdle()

        assertEquals(sampleText, testClipboard.getText()?.text)
    }

    @Test
    fun `exporting pdf displays progress indicator and disables pdf action`() {
        val sampleText = "CRONOGRAMA DE CARREGAMENTO - 15/01/2025"
        var sharePdfCalls = 0

        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = state().copy(exportText = sampleText, exportingPdf = true),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                    onSharePdf = { sharePdfCalls++ },
                )
            }
        }

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
            .performScrollTo()
            .assertIsDisplayed()
        val pdfButton = composeRule.onNodeWithText(copy(R.string.export_share_pdf))
        pdfButton.assertIsNotEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(0, sharePdfCalls)
    }

    @Test
    @Config(qualifiers = "w320dp-h1000dp")
    fun `export actions at 320dp with 2x font wrap and stay within bounds`() {
        val sampleText = "CRONOGRAMA DE CARREGAMENTO - 15/01/2025"

        composeRule.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = LocalDensity.current.density,
                    fontScale = 2.0f,
                ),
            ) {
                TrindadeTheme {
                    LoadingScreen(
                        state = responsiveState().copy(exportText = sampleText),
                        onBack = {},
                        onOpenHistory = {},
                        onStartAdding = {},
                        onCancelAdding = {},
                        onDriverSelected = {},
                        onVehicleSelected = {},
                        onConfirmAdd = {},
                        onDelete = {},
                        onEditEntry = { _, _ -> },
                        onLoadExport = {},
                    )
                }
            }
        }

        val copyNode = composeRule.onNodeWithText(copy(R.string.export_copy)).performScrollTo()
        assertWithinViewport(copyNode)
        val copyBounds = copyNode.getUnclippedBoundsInRoot()
        assertTrue("Copy button height >= 48dp at 2x", copyBounds.bottom - copyBounds.top >= 48.dp)

        val shareTextNode = composeRule.onNodeWithText(copy(R.string.export_share_text)).performScrollTo()
        assertWithinViewport(shareTextNode)
        val shareTextBounds = shareTextNode.getUnclippedBoundsInRoot()
        assertTrue("Share text button height >= 48dp at 2x", shareTextBounds.bottom - shareTextBounds.top >= 48.dp)

        val sharePdfNode = composeRule.onNodeWithText(copy(R.string.export_share_pdf)).performScrollTo()
        assertWithinViewport(sharePdfNode)
        val sharePdfBounds = sharePdfNode.getUnclippedBoundsInRoot()
        assertTrue("Share PDF button height >= 48dp at 2x", sharePdfBounds.bottom - sharePdfBounds.top >= 48.dp)
    }

    @Test
    fun `quick add fletero button opens form and submits name, plate, and phone`() {
        var quickAdded: Triple<String, String?, String?>? = null
        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = addingState(),
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                    onQuickAdd = { name, plate, phone -> quickAdded = Triple(name, plate, phone) },
                )
            }
        }

        val openBtn = composeRule.onNodeWithText("Novo fletero").performScrollTo()
        openBtn.assertIsDisplayed()
        openBtn.performClick()

        composeRule.onNodeWithText("Nome").performScrollTo().performTextInput("Novo Fletero")
        composeRule.onNodeWithText("Placa").performScrollTo().performTextInput("ABC-1234")
        composeRule.onNodeWithText(copy(R.string.drivers_phone)).performScrollTo().performTextInput("+55 (11) 98888-7777")

        composeRule.onNodeWithText("Salvar fletero").performScrollTo().performClick()
        assertEquals(Triple("Novo Fletero", "ABC-1234", "+55 (11) 98888-7777"), quickAdded)
    }

    @Test
    fun `already assigned driver for the day is excluded from driver selection in add form`() {
        val assignedDriver = FakeLoadingApi.defaultDrivers().first { it.id == FakeLoadingApi.FLETERO_DRIVER_ID }
        val stateWithAssigned = addingState().copy(
            schedules = listOf(FakeLoadingApi.entry(id = 1, slot = "06:00").copy(driverId = assignedDriver.id)),
        )
        composeRule.setContent {
            TrindadeTheme {
                LoadingScreen(
                    state = stateWithAssigned,
                    onBack = {},
                    onOpenHistory = {},
                    onStartAdding = {},
                    onCancelAdding = {},
                    onDriverSelected = {},
                    onVehicleSelected = {},
                    onConfirmAdd = {},
                    onDelete = {},
                    onEditEntry = { _, _ -> },
                    onLoadExport = {},
                )
            }
        }

        // The casa driver is not assigned, so it is displayed
        composeRule.onNodeWithText("Carlos").performScrollTo().assertIsDisplayed()
        // The fletero driver is assigned to 06:00, so it is excluded from the available drivers list
        composeRule.onNodeWithText("Fletero").assertDoesNotExist()
    }

    private companion object {
        /** Four rows of one day, each with its own id so no two identities read alike. */
        const val CLOSED_WINDOW_ID = 31
        const val READ_ONLY_STATED_ID = 32
        const val NOTHING_MARKED_ID = 33
        const val EDITABLE_ID = 34

        /** The day the grid is showing, which the way in has to carry to the editor. */
        const val DATE = "2026-09-18"

        /** The configured slots: exactly the four the rows sit in, in the server's order. */
        val SLOTS = listOf("04:00", "04:30", "06:00", "06:30")

        /** A row with an id of its own, so its identity line cannot be satisfied by another row's. */
        const val MOVED_ROW_ID = 41

        /** The slot that row sits in before the save, and the one the server reports after it. */
        const val SLOT_MOVED_FROM = "04:00"
        const val SLOT_MOVED_TO = "04:30"

        /**
         * The day's configured slots, in the order the grid draws them.
         *
         * `FakeLoadingApi`'s own three, which is what the delegated `timeSlots` answers with: written out
         * rather than read from the fake, because the leg below is about where a row lands among the blocks
         * the server configured, and a list taken from the fake could agree with a wrong grid.
         */
        val MOVED_SLOTS = listOf("04:00", "04:30", "05:00")
    }
}
