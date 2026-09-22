package com.trindade.app.loading

import android.content.Context
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.SchedulesResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.network.LoadingApi
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private fun copy(id: Int): String = context.getString(id)

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
    private val editable = row(id = EDITABLE_ID, slot = "06:30")

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
    ) : LoadingApi by FakeLoadingApi() {

        /** The dates asked for, in order: what an ordinary arrival must not lengthen. */
        val asked = mutableListOf<String>()

        override suspend fun schedules(date: String): Response<SchedulesResponse> {
            asked += date
            val slot = if (asked.size > 1) movesTo else movesFrom

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
    fun `the row a save moved is drawn in its new slot when the grid comes back`() {
        val api = DayThatMoves(movesFrom = SLOT_MOVED_FROM, movesTo = SLOT_MOVED_TO, rowId = MOVED_ROW_ID)
        val model = LoadingViewModel(LoadingRepository(api))
        val today = LoadingViewModel.saoPauloToday()
        val row = FakeLoadingApi.entry(MOVED_ROW_ID, SLOT_MOVED_FROM)

        // The two inputs `MainActivity` supplies: the save report the editor leaves behind, and the way the
        // grid hands that fact back once it has acted on it.
        val stale = mutableStateOf(false)
        val arrival = mutableStateOf(0)

        composeRule.setContent {
            TrindadeTheme {
                key(arrival.value) {
                    LoadingRoute(
                        onBack = {},
                        onOpenHistory = {},
                        onEditEntry = { _, _ -> },
                        stale = stale.value,
                        onStaleRead = { stale.value = false },
                        viewModel = model,
                    )
                }
            }
        }
        composeRule.waitForIdle()

        // One read, and the row drawn in the slot that read had it in: this arrival is the loading tab's
        // own, which means today, and the read the constructor started is that one. An arrival that asked
        // again here would already be the wrong grid.
        assertEquals(listOf(today), api.asked)
        assertEquals(SLOT_MOVED_FROM, drawnSlot(row))

        // The save: the editor reports it, the fact is set, and the grid arrives again on the day it was
        // already showing. The day is read again, so what is on screen is where the server now has the row
        // -- and the fact goes back with that read.
        stale.value = true
        arrival.value++
        composeRule.waitForIdle()

        assertEquals(listOf(today, today), api.asked)
        assertFalse("the arrival takes the fact with its read", stale.value)
        assertEquals(SLOT_MOVED_TO, drawnSlot(row))

        // And the arrival after that one -- the ordinary one -- reads nothing, which is the skip the grid
        // has always had and the direction this fix must not have cost.
        arrival.value++
        composeRule.waitForIdle()

        assertEquals(listOf(today, today), api.asked)
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
