package com.trindade.app.reports

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The detail screen's way into the edit surface, rendered rather than read.
 *
 * The screen is rendered with a state built by hand -- no ViewModel, no Hilt -- which is what its
 * stateless shape is for: it takes a `ReportDetailViewModel.UiState` and plain callbacks, so what is
 * under test is what the screen does with that state. What it does with `canEdit` and `readOnly` is the
 * claim: the whole report edit work turned on one rule, "a report the server did not mark editable is
 * read-only", and `canEdit = true` beside `readOnly = true` is an answer the server really can send --
 * the two are independent flags on the wire. A detail that read `canEdit` on its own would offer the way
 * in on exactly that report, and the edit surface, reading the explicit refusal first, would draw it with
 * no save at all: an action whose only possible outcome is the screen it opens doing nothing.
 *
 * The unit's own lanes could not see it. The edit screen's lane proves D6 from a report the two flags
 * already agree about, and the view model's lane proves the predicate's answer -- neither of them draws
 * the detail, so neither can see the detail and the edit surface answering one question two ways. That
 * is what puts this file in the rendered lane, and what the two tests below divide between them: one leg
 * is the contradictory answer, and the other is what makes it mean something.
 *
 * Three things about the file are the lane's own conventions, and they are the same three the other
 * screens' tests state:
 *
 *  * `GraphicsMode.NATIVE` is what makes the rendering real: text is measured by the framework's layout
 *    instead of by a shadow that returns a constant, and "displayed" turns on a node's size.
 *  * The copy is looked up from the resources the app ships -- `copy(R.string.x)` -- rather than typed
 *    here, so a change to a label fails this test instead of passing silently against a stale
 *    expectation.
 *  * Each test renders once, because a `ComposeContentTestRule` draws one composition. The two legs are
 *    therefore two tests rather than two renders, and the second is not decoration: "the action does not
 *    exist" is also true of a screen that drew nothing at all, which is why both tests assert the way
 *    back as well.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportDetailScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    /** One string this screen draws, looked up rather than typed. */
    private fun copy(id: Int): String = context.getString(id)

    /**
     * The report the state carries, with the two flags the screen's rule is about passed straight
     * through -- including as null, which is the shape the second test needs.
     *
     * Built on the fake's own created report so the fields these tests do not care about keep the shape
     * the server sends instead of a hand-built near-copy of it.
     */
    private fun report(canEdit: Boolean?, readOnly: Boolean?): ReportResponseReport =
        FakeReportsApi.createdReport().copy(canEdit = canEdit, readOnly = readOnly)

    /**
     * The state the screen has to be handed to compose at all, and nothing more than that.
     *
     * Every field below is one the screen reads while composing, which is why none of them can be left to
     * a default here:
     *
     *  * `report` -- the whole contents block is drawn from it: `turno.value` and `reportDate` in the
     *    heading, `user.displayName` under it, and one row per `items` entry and per `temperatures`
     *    entry.
     *  * `photos` -- the count in the photos heading, and one row per photo.
     *  * `loading` false and `message` null -- the message line is drawn when the state carries one, and
     *    a state that carried a sentence would be a second thing on screen.
     *  * `uploadingPhoto` false, `exportText` null and `loadingExport` false -- the add-photo action reads
     *    the first (through `canAddPhoto`), and the export section reads the other two.
     *
     * The report is deliberately empty: no items, no temperatures and -- per the photos field -- no
     * photos. That keeps the fixture to what the edit action's rule needs, and the fields the contracts
     * require are the ones the fake's own created report already fills, so the report stays the shape the
     * server sends. `reportId` is the one field not listed above, because nothing on this screen draws
     * it; it is set from the report anyway rather than left at its default, so the state is one the app
     * could really have produced.
     */
    private fun state(report: ReportResponseReport) = ReportDetailViewModel.UiState(
        reportId = report.id,
        loading = false,
        report = report,
        photos = emptyList(),
        uploadingPhoto = false,
        exportText = null,
        loadingExport = false,
        message = null,
    )

    /** The screen as the route draws it, with every callback a no-op. */
    private fun render(state: ReportDetailViewModel.UiState) {
        composeRule.setContent {
            TrindadeTheme {
                ReportDetailScreen(
                    state = state,
                    onAddPhoto = {},
                    onDeletePhoto = {},
                    onLoadExport = {},
                    onEdit = {},
                    onBack = {},
                )
            }
        }
    }

    /**
     * The contradictory answer the server can send, and the defect this file exists for: `canEdit` says
     * yes on its own, the explicit `readOnly` says no, and the way in must not be drawn.
     *
     * `assertDoesNotExist` rather than a disabled or absent assertion of some other shape, and the
     * difference is the same one the edit screen's own D6 test draws: a way in that is drawn and then
     * leads to a screen with no save is worse than no way in, because the operator is told the edit
     * exists and that something would have to change for it. The explicit flag is the server's answer
     * about the edit window, and it is the one that wins.
     *
     * The way back is asserted beside it so this leg cannot pass on an empty screen: `assertDoesNotExist`
     * is satisfied by a composition that drew nothing at all, and a run in which the screen failed to
     * compose is not a run in which the rule held.
     */
    @Test
    fun `a report the server refuses draws no way into the edit surface, even when canEdit says otherwise`() {
        render(state(report(canEdit = true, readOnly = true)))

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.report_edit)).assertDoesNotExist()
    }

    /**
     * The other side of the same rule, and what makes the assertion above say something: an explicit
     * `canEdit = true` with no `readOnly` at all is the only editable shape, and the way in is drawn.
     *
     * An absent `readOnly` is the case the predicate treats as "not refused" rather than as an absence to
     * fall back to, and it is the shape the server actually produces for an editable report -- the two
     * flags are optional in the contract, so a report that was explicitly marked editable is the one this
     * test hands over. A screen that withheld the action whenever either flag was absent would pass the
     * test above and fail this one.
     */
    @Test
    fun `a report the server marks editable draws the way into the edit surface`() {
        render(state(report(canEdit = true, readOnly = null)))

        composeRule.onNodeWithText(copy(R.string.report_back)).assertIsDisplayed()
        composeRule.onNodeWithText(copy(R.string.report_edit)).assertIsDisplayed()
    }
}
