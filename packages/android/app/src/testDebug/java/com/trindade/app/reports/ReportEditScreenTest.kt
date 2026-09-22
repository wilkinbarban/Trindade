package com.trindade.app.reports

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnySibling
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The edit screen, rendered rather than read.
 *
 * The screen is rendered with a state built by hand -- no ViewModel, no Hilt -- which is what its
 * stateless shape is for: it takes a `ReportEditViewModel.UiState` and plain callbacks, so what is
 * under test is what the screen does with that state. The three states below are the three the screen
 * has to get right, and the second is the one this file exists for. `D6` says that a report the server
 * refuses must not be offered an edit, and the difference between "the save is disabled" and "the save
 * is not drawn" is invisible to every other lane: nothing in a JVM test of the view model can see what a
 * screen draws, and a screen that left a greyed save on a read-only report would pass an assertion about
 * `canSubmit` while still telling the operator that the action exists and that something would fix it.
 *
 * Four things about this file are deliberate, and the first three are the lane's own conventions:
 *
 *  * `GraphicsMode.NATIVE` is what makes the rendering real: text is measured by the framework's layout
 *    instead of by a shadow that returns a constant. Every claim below is about a node being drawn,
 *    selected or enabled, and a node's size is what "drawn" turns on.
 *  * The screen scrolls itself -- its root is the `Column` with `verticalScroll` -- so `performScrollTo`
 *    always has a scrollable ancestor and every selection below can be scrolled to before it is asserted
 *    or tapped. A node outside the viewport is not displayed and cannot be tapped, because a tap is
 *    delivered at the node's own position.
 *  * The copy is looked up from the resources the app ships -- `copy(R.string.x)` -- rather than typed
 *    here, so a change to a label fails this test instead of passing silently against a stale
 *    expectation. The two shift chips are found by the history's own strings because those are the
 *    strings the screen draws: it shares them rather than spelling "Tarde" and "Noite" twice.
 *  * The check box is found by the row it is drawn in -- `isToggleable()` plus the label beside it --
 *    rather than by type, and this is the one selection here that has to be careful. Whether a Material
 *    `FilterChip` reports itself as toggleable is a detail of that component, and the screen draws two
 *    of them above the form; a bare `isToggleable()` would then be an assertion about whichever control
 *    happened to match, which is precisely the mistake a read-only test must not make.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportEditScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** One string this screen draws, looked up rather than typed. */
    private fun copy(id: Int): String = context.getString(id)

    // ---- The data the screen is handed, built from the generated contract types ----

    /**
     * One category of the contract, with both names set to the same string.
     *
     * The screen draws `namePt` -- the app has no language switch yet -- so setting `nameEs` to the same
     * string keeps a wrong pick from hiding behind a second name.
     */
    private fun category(
        id: Int,
        namePt: String,
        tasks: List<ReportCategoryTasksInner> = emptyList(),
    ) = CategoriesResponseCategoriesInner(
        id = id,
        parentCategoryId = null,
        namePt = namePt,
        nameEs = namePt,
        sortOrder = id,
        categoryType = CategoriesResponseCategoriesInner.CategoryType.check,
        tasks = tasks,
    )

    /** One task of the contract. `temperatureReadings` is 0 for the three types that have no readings. */
    private fun task(
        id: Int,
        categoryId: Int,
        namePt: String,
        taskType: ReportCategoryTasksInner.TaskType,
        temperatureReadings: Int = 0,
    ) = ReportCategoryTasksInner(
        id = id,
        categoryId = categoryId,
        namePt = namePt,
        nameEs = namePt,
        taskType = taskType,
        temperatureReadings = temperatureReadings,
    )

    private val checkTask = task(
        id = CHECK_TASK_ID,
        categoryId = CATEGORY_ID,
        namePt = "Lavar as mãos",
        taskType = ReportCategoryTasksInner.TaskType.check,
    )

    /**
     * The temperature task declares two readings, so the form has to draw two fields for it -- the one
     * the report carries a value for and the one it does not. A single-reading task would make "one field
     * per declared reading" indistinguishable from "one field, which happens to have a value".
     */
    private val temperatureTask = task(
        id = TEMPERATURE_TASK_ID,
        categoryId = CATEGORY_ID,
        namePt = "Câmara fria",
        taskType = ReportCategoryTasksInner.TaskType.temperature,
        temperatureReadings = 2,
    )

    private val categories = listOf(
        category(id = CATEGORY_ID, namePt = "Higiene", tasks = listOf(checkTask, temperatureTask)),
    )

    /**
     * The offers the server answers with.
     *
     * They are not drawn by this fixture -- it has no product task -- but they are part of a state the
     * app could actually have produced: `canSubmit` is false while the offers have not arrived, so a
     * state without them would make the save action in the first test unpressable.
     */
    private val offers = ProductsResponse(
        assai = listOf(ProductsResponse.Assai.Quadrada),
        normal = listOf(ProductsResponse.Normal.Nhoque_400g),
    )

    /**
     * The report the state carries, which is where the screen reads `canEdit` and `readOnly` from.
     *
     * Built on the fake's own created report so the fields this test does not care about keep the shape
     * the server sends, and the two flags are the only thing the two fixtures below change.
     */
    private val editableReport = FakeReportsApi.createdReport().copy(canEdit = true, readOnly = false)
    private val readOnlyReport = FakeReportsApi.createdReport().copy(canEdit = false, readOnly = true)

    /**
     * A settled state: not loading, with everything a finished load leaves behind.
     *
     * The notes carry the loaded text and the shift is one of the two, because both are seeded from the
     * report on load and the screen's job is to draw what it was handed.
     */
    private fun state(
        report: ReportResponseReport = editableReport,
        notes: String = NOTES,
        checks: Map<Int, Boolean> = mapOf(CHECK_TASK_ID to true),
        temperatures: Map<Int, List<String>> = mapOf(TEMPERATURE_TASK_ID to listOf("4.5", "")),
        message: String? = null,
    ) = ReportEditViewModel.UiState(
        loading = false,
        report = report,
        categories = categories,
        offers = offers,
        turno = "tarde",
        notes = notes,
        checks = checks,
        temperatures = temperatures,
        message = message,
    )

    // ---- Rendering ----

    /** The screen as the route draws it, with every callback a no-op unless a test hands one over. */
    private fun render(
        state: ReportEditViewModel.UiState,
        onSubmit: () -> Unit = {},
    ) {
        composeRule.setContent {
            TrindadeTheme {
                ReportEditScreen(
                    state = state,
                    onTurnoChange = {},
                    onNotesChange = {},
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
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

    /** The one check box, found by the row it is drawn in rather than by being the only toggleable node. */
    private fun checkBox() =
        composeRule.onNode(isToggleable() and hasAnySibling(hasText(checkTask.namePt)))

    // ---- The tests ----

    /**
     * The editable report: everything the load seeded is drawn, the loaded shift is the selected chip,
     * and the save action is the one thing the operator can send.
     *
     * The chip assertion is in two halves, and neither alone is the claim: the screen always draws two
     * chips, so "the chips are there" is true of a screen that selected neither or both. One half names
     * the selected one -- the report's own shift -- and the other counts the screen's selected nodes, so
     * "exactly one, and it is that one" is what is asserted.
     *
     * The save action is tapped exactly once, which is what proves the callback is wired to the press
     * rather than merely declared in the signature: an assertion on the button's existence would pass on
     * a button whose `onClick` was the empty lambda.
     */
    @Test
    fun `an editable report draws the shift, the notes, the form, and a save action that submits once`() {
        var submits = 0
        render(state(), onSubmit = { submits++ })

        composeRule.onNodeWithText(copy(R.string.report_edit)).performScrollTo().assertIsDisplayed()

        // The chips are drawn and the loaded shift is the selected one. Which chip that is comes from
        // the label's own node; that no *second* chip is selected comes from counting the screen's
        // selected nodes, because a Material selection that wrote its `Selected` semantics only when
        // selected would leave an unselected chip indistinguishable from one with no selection state at
        // all -- and "the other chip is not selected" is the half that would then go unasserted.
        composeRule.onNodeWithText(copy(R.string.history_turno_tarde)).performScrollTo().assertIsSelected()
        composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
            .assertCountEquals(1)
        composeRule.onNodeWithText(copy(R.string.history_turno_noite)).performScrollTo().assertIsDisplayed()

        // The field's own value is what is asserted, and the node it is on is the field: a text field
        // merges its label and its value into one node, so finding the text is finding the control.
        displayed(NOTES)

        displayed(checkTask.namePt)
        displayed(temperatureTask.namePt)
        // One field per declared reading, so the second slot the report has no value for is drawn as an
        // empty field the operator can fill rather than as a field that is not there.
        displayed("Leitura 1")
        displayed("Leitura 2")

        composeRule.onNodeWithText(copy(R.string.report_update))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        composeRule.waitForIdle()

        assertEquals(1, submits)
    }

    /**
     * `D6`, and the assertion this file exists for: on a report the server refuses, no save action is
     * drawn at all.
     *
     * `assertDoesNotExist` rather than `assertIsNotEnabled`, and the difference is the whole decision. A
     * disabled action is still an offer -- it says "this exists and something would have to change" --
     * and here nothing would: the boundary is the server's edit window, which no input on this screen can
     * move. The screen therefore withholds the action, and the test has to be able to tell the two
     * renderings apart, which only a "does not exist" assertion can do.
     *
     * The controls are asserted disabled as well, because the two halves are one claim: the screen does
     * not offer an edit, and it does not collect one either. A live field on a read-only report would
     * gather typing that no action here can send, which reads as a broken screen rather than as a closed
     * one.
     */
    @Test
    fun `a read-only report disables its controls and draws no save action at all`() {
        render(state(report = readOnlyReport))

        // The form's own controls: the check box and the two temperature fields the fixture owes. The
        // box is found by its row (see `checkBox`), and each field by the label it draws.
        checkBox().performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Leitura 1").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("Leitura 2").performScrollTo().assertIsNotEnabled()

        // This screen's own two: the shift and the notes, which the form does not own and which the
        // read-only report must not leave collectable either.
        composeRule.onNodeWithText(copy(R.string.history_turno_tarde)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(copy(R.string.history_turno_noite)).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText(NOTES).performScrollTo().assertIsNotEnabled()

        composeRule.onNodeWithText(copy(R.string.report_update)).assertDoesNotExist()
    }

    /**
     * The message line, drawn the way the generator draws its own: the failure the view model resolved
     * has to reach the operator, and a screen that kept it in the state and drew nothing would leave a
     * refusal looking like a press that did nothing at all.
     *
     * The state is otherwise the editable one, because that is the only state in which the line can
     * arrive: it is set by a refused save or by a load that found nothing, and only the first of those
     * has a form on screen to explain.
     */
    @Test
    fun `the message the state carries is drawn`() {
        render(state(message = MESSAGE))

        displayed(MESSAGE)
    }

    private companion object {
        const val CATEGORY_ID = 1
        const val CHECK_TASK_ID = 11
        const val TEMPERATURE_TASK_ID = 12

        /** The note the report carried, which the field has to show as it is. */
        const val NOTES = "Faltou o lacre"

        /**
         * The message the last test draws: the sentence a save that never reached the server leaves.
         *
         * It is a sentence the state can genuinely carry on an editable report, which the refusal the
         * read-only screen would leave could not be -- that one arrives with no action to explain, and
         * the message line is drawn on the screen that still has one.
         */
        const val MESSAGE = "Sem conexão com o servidor. Verifique a rede e tente de novo."
    }
}
