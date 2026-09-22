package com.trindade.app.loading

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The loading edit screen, rendered rather than read.
 *
 * The screen is rendered with a state built by hand -- no ViewModel, no Hilt -- which is what its
 * stateless shape is for: it takes a `LoadingEditViewModel.UiState` and plain callbacks, so what is
 * under test is what this screen does with that state. The arrangement of the test follows the reports'
 * own edit lane, for the reason that screen shares this one's shape, and the two rules this file exists
 * for are the two a view-model test cannot reach at all:
 *
 *  * **A read-only arrival draws no save action.** Not a disabled one. A disabled action is still an
 *    offer -- it says "this exists and something would have to change" -- and the loading window is the
 *    server's, which no input on this screen moves. Only a rendering can tell "no button" from "a greyed
 *    button", so `assertDoesNotExist` is the assertion that carries it, beside an editable arrival whose
 *    save is drawn so that a screen which never draws one cannot pass.
 *  * **The save is exactly as live as the state says.** The seed is a row whose slot has not moved, so
 *    the save is drawn dead; moving the picker to another slot makes it live; moving it back makes it
 *    dead again. The rule behind it (`canSubmit`) is the view model's and is asserted there -- what is
 *    asserted here is that this screen reads it rather than deciding for itself.
 *
 * Four things about this file are deliberate, and the first three are the lane's own conventions:
 *
 *  * `GraphicsMode.NATIVE` is what makes the rendering real: text is measured by the framework's layout
 *    instead of by a shadow that returns a constant, and every claim below is about a node being drawn,
 *    enabled or absent, which a node's size is what "drawn" turns on.
 *  * The screen scrolls itself -- its root is the `Column` with `verticalScroll` -- so `performScrollTo`
 *    always has a scrollable ancestor and every selection can be brought into the window before it is
 *    asserted or tapped.
 *  * The copy is looked up from the resources the app ships -- `copy(R.string.x)` -- rather than typed
 *    here, so a change to a label fails this test instead of passing against a stale expectation. The
 *    one string that is typed out is the server's own refusal and the view model's own not-found
 *    sentence, both of which live in private constants on purpose (see below).
 *  * The picker's options are found by the whole label they draw -- the slot and the count, on one
 *    control -- rather than by being the only clickable thing on the screen. The save is also clickable
 *    and so is the way back, and a bare `hasClickAction()` would be an assertion about whichever control
 *    happened to match, which is precisely the mistake a test about the save must not make. Asserting
 *    both halves of the label on one node is also what ties a count to the slot it belongs to: a set of
 *    counts floating somewhere on the screen would satisfy either half alone.
 *
 * The state the composition reads is held by the test (`drawn`) rather than handed in as a value, so a
 * tap on the picker is visible to the save on the next frame: the callback writes the selection into the
 * same state the screen is being drawn from, the way the view model's own would, and one test can then
 * follow the save from dead to alive through the control that moves it.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LoadingEditScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** One string this screen draws, looked up rather than typed. */
    private fun copy(id: Int, vararg formatArgs: Any): String = context.getString(id, *formatArgs)

    /** The counter one option carries: the same resource and the same denominator as the grid's own. */
    private fun counter(fleteros: Int): String = copy(R.string.loading_fleteros, fleteros, FleteroQuota.LIMIT)

    // ---- The data the screen is handed, built from the generated contract types ----

    /**
     * One row of the day, with the two permission flags the server computes together passed straight
     * through, exactly as `LoadingEditViewModel` hands them over.
     */
    private fun row(
        id: Int = ENTRY_ID,
        slot: String = "04:00",
        type: SchedulesResponseSchedulesInner.DriverType = SchedulesResponseSchedulesInner.DriverType.fletero,
        canEdit: Boolean = true,
        readOnly: Boolean = false,
    ) = FakeLoadingApi.entry(id = id, slot = slot, type = type).copy(canEdit = canEdit, readOnly = readOnly)

    private val editableEntry = row()
    private val readOnlyEntry = row(canEdit = false, readOnly = true)

    /**
     * The options the state carries, written out here rather than computed.
     *
     * The screen is handed them and never derives them: the figure each one holds is
     * `FleteroQuota`'s "if this entry moved here, how many would there be", computed by
     * `LoadingEditViewModel`, and `LoadingEditViewModelTest` is where that arithmetic is asserted. What
     * this file is about is what the screen draws with the list it is given.
     */
    private fun offers(vararg counts: Pair<String, Int>): List<LoadingEditViewModel.SlotOffer> =
        counts.map { LoadingEditViewModel.SlotOffer(timeSlot = it.first, fleteros = it.second) }

    /** The day's slots with the counts a day of this shape would leave in each of them. */
    private val day = offers("04:00" to 2, "04:30" to 2, "06:00" to 1, "09:00" to 1)

    /**
     * A settled arrival: the row, the day's slots, and the row's own slot seeded as the selection.
     *
     * The flags travel with the entry because that is where they come from: the state's `readOnly` is the
     * entry's own answer, and a fixture that set them apart would be rendering a state the view model
     * cannot produce.
     */
    private fun settled(
        entry: SchedulesResponseSchedulesInner? = editableEntry,
        offers: List<LoadingEditViewModel.SlotOffer> = day,
        message: String? = null,
    ) = LoadingEditViewModel.UiState(
        loading = false,
        entry = entry,
        offers = if (entry == null) emptyList() else offers,
        timeSlot = entry?.timeSlot,
        message = message,
    )

    // ---- Rendering ----

    /**
     * The state the composition reads, held by the test so the callbacks below can write into it the way
     * the view model's own would.
     */
    private lateinit var drawn: MutableState<LoadingEditViewModel.UiState>

    private fun render(state: LoadingEditViewModel.UiState, onSubmit: () -> Unit = {}) {
        drawn = mutableStateOf(state)
        composeRule.setContent {
            TrindadeTheme {
                LoadingEditScreen(
                    state = drawn.value,
                    onTimeSlotChange = { slot -> drawn.value = drawn.value.copy(timeSlot = slot) },
                    onSubmit = onSubmit,
                    onBack = {},
                )
            }
        }
    }

    /** One label, asserted as drawn after being scrolled to. */
    private fun displayed(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    /**
     * The line the screen draws for the row it is editing.
     *
     * It is composed the way the screen composes it -- the grid's own `describe`, then the row's slot --
     * and not spelled out as a string here, so the two stay one expression: the row the operator tapped in
     * the grid and the row this screen shows them are identified by the same function.
     */
    private fun rowLine(entry: SchedulesResponseSchedulesInner): String =
        listOf(describe(entry), entry.timeSlot).joinToString(" · ")

    /**
     * One option as the screen draws it and the operator reads it: one control whose label is the slot,
     * with the count that slot would hold beside it.
     *
     * [marked] selects the counter that carries the over-limit mark, so the two spellings of the same
     * figure are distinguishable and "marked" cannot be confused with "drawn".
     */
    private fun option(slot: String, fleteros: Int, marked: Boolean = false) =
        composeRule.onNode(
            hasText(slot) and hasText(if (marked) "${counter(fleteros)} ⚠" else counter(fleteros)),
        )

    private fun save() = composeRule.onNodeWithText(copy(R.string.loading_edit_save))

    // ---- The tests ----

    /**
     * The editable arrival: the row being edited is drawn, the day's slots are offered, and the save
     * action is drawn.
     *
     * This is the positive control for the read-only test below and for itself: an empty screen draws no
     * save either, and a file whose only save assertion was `assertDoesNotExist` would pass against a
     * screen that drew nothing at all. What it asserts about the save is only that it is there -- how live
     * it is belongs to the test below, and this one would be asserting the other rule if it read the
     * button's enabled state.
     */
    @Test
    fun `an editable row draws the row it is editing, the day's slots, and a save action`() {
        render(settled())

        displayed(rowLine(editableEntry))
        displayed(copy(R.string.loading_pick_slot))
        day.forEach { displayed(it.timeSlot) }

        save().performScrollTo().assertIsDisplayed()
    }

    /**
     * The rule this file exists for: on a row the server closed, the save action is not drawn at all.
     *
     * `assertDoesNotExist` rather than `assertIsNotEnabled`, and the difference is the whole decision --
     * see this screen's own comment for why the boundary the server draws is stated by withholding the
     * action rather than by refusing it. The row and its options are asserted just before it, so what the
     * missing save is read against is a screen that drew something: a screen that drew nothing would fail
     * the two assertions above rather than pass the one below.
     *
     * The picker is asserted dead as well, because the two halves are one claim: the screen does not offer
     * an edit, and it does not collect one either. A live picker would gather a choice this screen has no
     * action to send, which reads as a broken screen rather than as a closed one.
     */
    @Test
    fun `a read-only arrival draws no save action at all, and leaves the picker unselectable`() {
        render(settled(entry = readOnlyEntry))

        displayed(rowLine(readOnlyEntry))
        option("04:00", 2).performScrollTo().assertIsNotEnabled()
        option("09:00", 1).performScrollTo().assertIsNotEnabled()

        save().assertDoesNotExist()
    }

    /**
     * The save is dead until the slot moves, alive once it has, and dead again when it moves back.
     *
     * The middle of the three is what makes the two dead ones mean something: without it this test would
     * pass against a form whose save is never offered, and without the third it would pass against one
     * whose save is offered for a row that did not move. The rule itself -- `canSubmit`, which is the
     * web's own `disabled={saving || editSlot === entry.time_slot}` -- is the view model's and is asserted
     * in its own test; what is asserted here is that this screen reads it, since a screen that recomputed
     * it from `state.timeSlot` would pass every view-model test and still disagree with the state.
     *
     * The tap in the middle counts one submission, which is what proves the callback is wired to the press
     * rather than merely declared in the signature.
     */
    @Test
    fun `the save is dead until the slot changes and alive after, and dead again if it goes back`() {
        var submits = 0
        render(settled(), onSubmit = { submits++ })

        save().assertIsNotEnabled()

        option("06:00", 1).performClick()
        composeRule.waitForIdle()
        save().assertIsEnabled().performClick()
        composeRule.waitForIdle()
        assertEquals(1, submits)

        option("04:00", 2).performClick()
        composeRule.waitForIdle()
        save().assertIsNotEnabled()
    }

    /**
     * The picker's options: each of the day's slots beside the count that slot would hold with this row in
     * it, and the mark on the one that would pass the limit.
     *
     * Each option is asserted as a pair -- a node for the slot that has that count beside it -- because the
     * two are one claim: a list of counts floating somewhere on the screen, or a list of slots with the
     * counts wrong, passes either half alone. The counters here are all different for the same reason, so
     * no assertion can be satisfied by another option's figure.
     *
     * The boundary is asserted on both sides of it: four fleteros in a window is past
     * `FleteroQuota.LIMIT` and is marked, and the marked spelling is asserted absent for the same option so
     * that a screen which marked every option would fail. The mark is information and not a gate, which is
     * the screen's own rule and is asserted as behaviour: the marked option is still enabled, and picking
     * it moves the save from dead to alive.
     */
    @Test
    fun `the picker draws each option with its count, and marks only the one past the limit`() {
        render(settled(offers = offers("04:00" to 2, "04:30" to 3, "06:00" to 4, "09:00" to 1)))

        option("04:00", 2).assertIsDisplayed()
        option("04:30", 3).assertIsDisplayed()
        option("09:00", 1).assertIsDisplayed()

        option("06:00", 4, marked = true).assertIsDisplayed()
        option("06:00", 4).assertDoesNotExist()

        option("06:00", 4, marked = true).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        save().assertIsEnabled()
    }

    /**
     * The message the state carries, drawn as it stands.
     *
     * The sentence is the server's own refusal, character for character, and it is typed here rather than
     * read from a constant because the one the app holds is private to the view model's companion: writing
     * it out is what makes this an assertion about the app agreeing with `loading.service.ts` rather than
     * about the app agreeing with itself. The second half is what makes it an assertion about the state
     * rather than about a sentence the screen happens to draw -- a message that arrives later replaces the
     * one before it, and the first is gone.
     */
    @Test
    fun `the message the state carries is drawn as it stands`() {
        render(settled(message = SERVER_WINDOW_REFUSAL))

        displayed(SERVER_WINDOW_REFUSAL)

        drawn.value = drawn.value.copy(message = LoadingViewModel.UNREACHABLE)
        composeRule.waitForIdle()

        displayed(LoadingViewModel.UNREACHABLE)
        composeRule.onNodeWithText(SERVER_WINDOW_REFUSAL).assertDoesNotExist()
    }

    /**
     * A read in flight, in the loading screens' own idiom: the spinner, and nothing of the arrival before
     * it.
     *
     * The entry is deliberately left in the state, which is what the view model's own `load` does while it
     * reads -- the instance is scoped to the activity, so the row a previous arrival loaded is still
     * standing when the next one starts. Drawing it under the spinner would show the operator a row they
     * are not editing, under the arrival they asked for, and this is the assertion that refuses it.
     */
    @Test
    fun `a read in flight draws the spinner and not the row a previous arrival left`() {
        render(
            LoadingEditViewModel.UiState(
                loading = true,
                entry = editableEntry,
                offers = day,
                timeSlot = editableEntry.timeSlot,
            ),
        )

        composeRule.onNode(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)).assertIsDisplayed()

        composeRule.onNodeWithText(rowLine(editableEntry)).assertDoesNotExist()
        save().assertDoesNotExist()
    }

    /**
     * A day that does not carry the row: the sentence the state carries, and no form.
     *
     * The two failures the view model tells apart are a row that is gone and a server that was never
     * reached, and this screen's part in keeping them apart is to draw whichever sentence arrived. The
     * sentence below is the view model's own not-found answer, written out here because the constant that
     * holds it is private; no picker and no save are drawn, because there is no row for either to be about.
     */
    @Test
    fun `a day that does not carry the row draws its sentence and no form`() {
        render(settled(entry = null, message = NOT_FOUND))

        displayed(NOT_FOUND)
        composeRule.onNodeWithText(copy(R.string.loading_pick_slot)).assertDoesNotExist()
        save().assertDoesNotExist()
    }

    private companion object {
        /** The row the screen opened on. Distinct from the fake's own ids, which none of these use. */
        const val ENTRY_ID = 12

        /**
         * The window's refusal, which is `loading.service.ts`'s sentence and not the app's.
         *
         * `LoadingEditViewModel.OUTSIDE_EDIT_WINDOW` holds it in a private companion, so this copy is the
         * only thing a test in this package can compare against -- which is exactly what makes the
         * assertion worth making: the operator has to read the server's own words about a rule this client
         * deliberately does not implement.
         */
        const val SERVER_WINDOW_REFUSAL =
            "Registro somente leitura. Apenas o criador pode editar durante a primeira hora."

        /** The view model's own answer for a day that answered without this row, likewise private there. */
        const val NOT_FOUND = "Lançamento não encontrado."
    }
}
