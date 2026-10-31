package com.trindade.app.reports

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.R
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The generator screen, rendered rather than read.
 *
 * `ReportFormTest` renders `CategoryForm` and hands it its children itself, so it can prove the form
 * draws a nested category but never that the screen passes one: the screen's own line --
 * `children = state.categories.filter { it.parentCategoryId == category.id }` -- is handed nothing by
 * anyone else, and the form lane cannot see it at all. A screen that dropped that argument would go on
 * drawing every root's own tasks with the form's tests still green, while the child categories the
 * endpoint serves -- the active children of a category, in production as much as in this fixture --
 * disappeared from the only surface that shows them. The first test below is that claim.
 *
 * The screen is rendered with a state built by hand -- no ViewModel, no Hilt -- which is what its
 * stateless shape is for: it takes a `ReportGeneratorViewModel.UiState` and plain callbacks, so what is
 * under test is the wiring the screen does with that state, not the view model that fills it.
 *
 * Three things about this file are deliberate, and the first two are the lane's conventions:
 *
 *  * `GraphicsMode.NATIVE` is what makes the rendering real: text is measured by the framework's layout
 *    instead of by a shadow that returns a constant. Every claim below is about a node existing and
 *    being displayed, and a node's size is what "displayed" turns on.
 *  * The screen scrolls itself -- its root is the `LazyColumn` -- so there is no scroll container for
 *    the test to add, and each selection is still scrolled to before it is asserted. That works here
 *    because the categories below are small enough that the whole list composes inside the 1000dp-tall
 *    window; a lazy list only composes what it draws, and a node that was never composed cannot be
 *    found, let alone scrolled to. A taller fixture would need a different selection strategy, not a
 *    different assertion.
 *  * The categories are shaped the way the endpoint serves them rather than the way a minimal fixture
 *    would: the list is ordered `COALESCE(parent_category_id, id), sort_order, id`, so a child arrives
 *    right after the root it belongs to, which is why the second test's child is the third entry and
 *    carries an id higher than its parent's. That is the order the screen's filter walks, and a child
 *    parked at the end of the list would test the filter less.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportGeneratorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun copy(id: Int, vararg args: Any): String = context.getString(id, *args)

    // ---- The data the screen is handed, built from the generated contract types ----

    /**
     * One category of the contract, with both names set to the same string.
     *
     * The screen draws `namePt` -- the app has no language switch yet -- so a test that reads Portuguese
     * copy is reading what the screen draws, and setting `nameEs` to the same string keeps that honest
     * rather than leaving a second name that would hide a wrong pick.
     */
    private fun category(
        id: Int,
        namePt: String,
        parentCategoryId: Int? = null,
        tasks: List<ReportCategoryTasksInner> = emptyList(),
    ) = CategoriesResponseCategoriesInner(
        id = id,
        parentCategoryId = parentCategoryId,
        namePt = namePt,
        nameEs = namePt,
        sortOrder = id,
        categoryType = CategoriesResponseCategoriesInner.CategoryType.check,
        tasks = tasks,
    )

    /**
     * One task of the contract.
     *
     * The type and the reading count default to a check task with no readings, which is the shape every
     * fixture in this file but the temperature ones uses -- the three types that have no readings all draw
     * as a check, and the temperature fixtures say both of their own.
     */
    private fun task(
        id: Int,
        categoryId: Int,
        namePt: String,
        taskType: ReportCategoryTasksInner.TaskType = ReportCategoryTasksInner.TaskType.check,
        temperatureReadings: Int = 0,
    ) = ReportCategoryTasksInner(
        id = id,
        categoryId = categoryId,
        namePt = namePt,
        nameEs = namePt,
        taskType = taskType,
        temperatureReadings = temperatureReadings,
    )

    /**
     * The settled state the view model leaves behind: the categories the endpoint served, the offers
     * that arrived with them, and the shift the server detected.
     *
     * Only the categories change between the tests below, so everything else is fixed here, and fixed
     * to what a finished load looks like -- `loading = false`, offers present, a shift -- so that no
     * assertion can pass by being handed a state the app could not have produced. The offers carry two
     * real product names rather than an empty pair for the same reason: they are what the endpoint
     * answers with, and the two product tasks this fixture has no use for are still what a check task
     * beside them would draw from.
     */
    private fun state(categories: List<CategoriesResponseCategoriesInner>) =
        ReportGeneratorViewModel.UiState(
            loading = false,
            categories = categories,
            offers = ProductsResponse(
                assai = listOf(ProductsResponse.Assai.Quadrada, ProductsResponse.Assai.Rolo_500),
                normal = listOf(ProductsResponse.Normal.Nhoque_400g),
            ),
            turno = "tarde",
        )

    // ---- Rendering ----

    /** The screen as the route draws it, with every callback a no-op: nothing here taps anything. */
    private fun render(state: ReportGeneratorViewModel.UiState) {
        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = state,
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
                    onNotesChange = {},
                    onSubmit = {},
                    onOpenHistory = {},
                    onRetry = {},
                    onSyncErp = {},
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
     * The generator carries the same card the report's detail screen carries, in the same place: under the
     * temperatures. Before this it drew the massas only, so the day's receipts were reachable from the browser
     * and from a report that already existed -- never from the screen the operator uses to file one.
     */
    @Test
    fun `the day's ERP movements are drawn under the temperatures`() {
        render(state(listOf(category(1, "Cozinha"))))

        displayed(context.getString(R.string.report_erp_movements_title))
        displayed(context.getString(R.string.report_receipts_title))
    }

    /** The answer to the card's action is drawn under it, where the action is. */
    @Test
    fun `the card reports what the last sync answered`() {
        render(state(listOf(category(1, "Cozinha"))).copy(syncMessage = "Movimentações do ERP atualizadas."))

        displayed("Movimentações do ERP atualizadas.")
    }

    /**
     * The wiring this file exists for: the screen hands each root its own children, so a child category
     * and the task inside it are drawn.
     *
     * The child's task is the assertion that carries the weight, because it is the row the screen used
     * to lose: `items(state.categories.filter { it.parentCategoryId == null })` draws the roots only, and
     * the child's task reaches the screen through the `children` argument beside it. Drop that argument
     * and this is the test that fails -- `ReportFormTest` renders the form and not the screen, so it is
     * handed its children directly and cannot see the argument at all.
     *
     * The child's own name is asserted as well as its task's, because those are the two rows that go
     * missing together: a form that drew the child's tasks under the parent's heading could still submit
     * them, but it would be answering under the wrong shelf.
     */
    @Test
    fun `the screen draws a child category and its task beside their parent`() {
        val parentTask = task(id = 11, categoryId = 8, namePt = "Lavar as mãos")
        val childTask = task(id = 12, categoryId = 9, namePt = "Conferir validade")

        render(
            state(
                listOf(
                    category(id = 8, namePt = "Higiene", tasks = listOf(parentTask)),
                    category(
                        id = 9,
                        namePt = "Caixas pequenas",
                        parentCategoryId = 8,
                        tasks = listOf(childTask),
                    ),
                ),
            ),
        )

        displayed("Higiene")
        displayed(parentTask.namePt)
        displayed("Caixas pequenas")
        displayed(childTask.namePt)
    }

    /**
     * The same wiring when the children have to be matched to the right root, read off the layout rather
     * than off the node's existence.
     *
     * The child belongs to the second root, and the three tasks below are the ones whose vertical order
     * says where it was drawn: the first root's own task, the child's own root's task, and the child's.
     * Presence alone was not enough here, and this is the whole reason the test is written this way: a
     * screen that drew the child under whichever root it happened to be iterating -- the first one, say,
     * because the lookup was handed the wrong key -- still draws the child's task somewhere, so an
     * `assertIsDisplayed` on it passes. What refuses that reading is where the row ended up: with the
     * child drawn under its own root it sits below that root's own task, and drawn under the first root
     * it would sit above it, between the first root's task and the child's real parent.
     *
     * The fixture also had to move for this: the child used to belong to the first root, where `displayed`
     * and a placement both say the same thing, because the first root is drawn first either way.
     *
     * The child's own name and its task's are both asserted, as in the test above: those are the two rows
     * that go missing together, and the placement claim is about the task's row.
     */
    @Test
    fun `the child is drawn with the root it belongs to, not with whichever root comes first`() {
        val firstRootTask = task(id = 11, categoryId = 8, namePt = "Lavar as mãos")
        val parentRootTask = task(id = 31, categoryId = 10, namePt = "Conferir a geladeira")
        val childTask = task(id = 22, categoryId = 21, namePt = "Conferir validade")

        render(
            state(
                listOf(
                    category(id = 8, namePt = "Higiene", tasks = listOf(firstRootTask)),
                    category(id = 10, namePt = "Recepção", tasks = listOf(parentRootTask)),
                    category(
                        id = 21,
                        namePt = "Caixas pequenas",
                        parentCategoryId = 10,
                        tasks = listOf(childTask),
                    ),
                ),
            ),
        )

        displayed(childTask.namePt)
        displayed(parentRootTask.namePt)
        displayed("Caixas pequenas")

        // The bounds come back in dp, not in pixels: `getBoundsInRoot()` answers in `Dp` while the
        // neighbouring `SemanticsNode.boundsInRoot` is the API that answers in pixels.
        val firstRootTaskTop: Dp = composeRule.onNodeWithText(firstRootTask.namePt).getBoundsInRoot().top
        val parentRootTaskTop: Dp = composeRule.onNodeWithText(parentRootTask.namePt).getBoundsInRoot().top
        val childTaskTop: Dp = composeRule.onNodeWithText(childTask.namePt).getBoundsInRoot().top

        // Every row is drawn top to bottom, so a row that is lower on the screen has the greater `top`.
        // The child is under its own root's task, which is where its root's block is.
        assertTrue(
            "the child's task (top ${childTaskTop.value}dp) is not under the task of the root it belongs " +
                "to (top ${parentRootTaskTop.value}dp), so it was not drawn with that root",
            childTaskTop > parentRootTaskTop,
        )
        // And below the first root's task as well, which is the other row above the child's own root:
        // under both rows is the placement the wiring produces, and between the two is the placement a
        // child drawn inside the first root's block would have. (The comparison is against the first
        // root's task's `top` and reads "greater than": the first root is drawn first, so its task's top
        // is the smallest on the screen and a child below it can only be greater, never smaller. The
        // task statement asked for "smaller" here, which no layout can produce; the claim it names,
        // "not placed with a root that is not its own", is the one asserted.)
        assertTrue(
            "the child's task (top ${childTaskTop.value}dp) is not below the first root's task (top " +
                "${firstRootTaskTop.value}dp), so it is drawn between the two rows instead of under them",
            childTaskTop > firstRootTaskTop,
        )
    }

    @Test
    fun `history action and submit button meet 48dp touch targets and invoke callbacks`() {
        var openedHistory = 0
        var submitted = 0
        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = state(listOf(category(id = 8, namePt = "Higiene"))),
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
                    onNotesChange = {},
                    onSubmit = { submitted++ },
                    onOpenHistory = { openedHistory++ },
                    onRetry = {},
                )
            }
        }

        val historyNode = composeRule.onNodeWithText(copy(R.string.report_history))
        historyNode.assertIsDisplayed()
        val historyBounds = historyNode.getUnclippedBoundsInRoot()
        assertTrue(
            "History height must be >= 48dp but was ${historyBounds.bottom - historyBounds.top}",
            historyBounds.bottom - historyBounds.top >= 48.dp,
        )
        assertTrue(
            "History width must be >= 48dp but was ${historyBounds.right - historyBounds.left}",
            historyBounds.right - historyBounds.left >= 48.dp,
        )
        historyNode.performClick()
        assertEquals(1, openedHistory)

        val submitNode = composeRule.onNodeWithText(copy(R.string.report_submit))
        submitNode.performScrollTo().assertIsDisplayed()
        val submitBounds = submitNode.getUnclippedBoundsInRoot()
        assertTrue(
            "Submit height must be >= 48dp but was ${submitBounds.bottom - submitBounds.top}",
            submitBounds.bottom - submitBounds.top >= 48.dp,
        )
        submitNode.performClick()
        assertEquals(1, submitted)
    }

    // ---- The prerequisites, said out loud (AND-02) ----

    /**
     * The rule this pair exists for: a form that cannot be answered says why instead of being drawn blank.
     *
     * The failure it closes is quiet. The screen drew its form from whatever the reads left behind, so a
     * read that failed left an empty collection and the operator saw a page with a dead button: to them
     * that is a day with no tasks, which is a different fact from a read that never arrived. The reasons
     * come from the screen's own resources -- a Portuguese copy the test reads through the same catalogue
     * the app draws from.
     */
    @Test
    fun `a failed read says what is missing and offers the way to try again, and draws no form`() {
        var retried = 0
        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = state(listOf(category(id = 8, namePt = "Higiene"))).copy(
                        missing = listOf(
                            ReportGeneratorViewModel.Prerequisite.SHIFT,
                            ReportGeneratorViewModel.Prerequisite.CATEGORIES,
                        ),
                    ),
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
                    onNotesChange = {},
                    onSubmit = {},
                    onOpenHistory = {},
                    onRetry = { retried++ },
                )
            }
        }

        displayed(copy(R.string.report_missing_shift))
        displayed(copy(R.string.report_missing_categories))

        // The copy of the form the read could not supply is not drawn at all.
        composeRule.onNodeWithText("Higiene").assertDoesNotExist()

        composeRule.onNodeWithText(copy(R.string.report_retry)).performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `an incomplete read draws the form it did get and says what cannot be answered`() {
        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = state(listOf(category(id = 8, namePt = "Higiene"))).copy(
                        offers = null,
                        missing = listOf(ReportGeneratorViewModel.Prerequisite.OFFERS),
                    ),
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
                    onNotesChange = {},
                    onSubmit = {},
                    onOpenHistory = {},
                    onRetry = {},
                    onSyncErp = {},
                )
            }
        }

        // The sections the server did supply are drawn: the form is incomplete, not useless.
        displayed("Higiene")
        displayed(copy(R.string.report_missing_offers))
        composeRule.onNodeWithText(copy(R.string.report_submit)).assertIsNotEnabled()
    }

    @Test
    fun `the screen draws the notes field and displays the initial value`() {
        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = state(listOf(category(id = 8, namePt = "Higiene"))).copy(
                        notes = "Observação do turno",
                    ),
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
                    onNotesChange = {},
                    onSubmit = {},
                    onOpenHistory = {},
                    onRetry = {},
                    onSyncErp = {},
                )
            }
        }

        displayed(copy(R.string.report_notes))
        composeRule.onNodeWithText("Observação do turno").performScrollTo().assertIsDisplayed()
    }

    // ---- The readings the form owes (S2/S3) ----

    /** The catalogue's own temperature task: the first of the four, and the one that declares two readings. */
    private fun coldTask() = task(
        id = 5,
        categoryId = 2,
        namePt = "Câmara Principal",
        taskType = ReportCategoryTasksInner.TaskType.temperature,
        temperatureReadings = 2,
    )

    /** The screen's settled state with one temperature task in it and the readings it is handed. */
    private fun temperatureState(readings: List<String>) = state(
        listOf(category(id = 2, namePt = "Temperaturas", tasks = listOf(coldTask()))),
    ).copy(temperatures = mapOf(5 to readings))

    /**
     * The rule the absence of which the operator lived through: a form that owes a reading says so, and the
     * save it cannot honour is not drawn as a live action.
     *
     * The sentence is the server's own, from the same resource the web draws (`reports.temperaturesRequired`),
     * and where it sits is the point: above the action, so the reason for a withheld button is read where the
     * operator is looking for it.
     */
    @Test
    fun `a form that owes a reading does not show temperature warning prematurely on clean open`() {
        render(temperatureState(listOf("", "")))

        composeRule.onNodeWithText(copy(R.string.report_temperatures_required)).assertDoesNotExist()
    }

    @Test
    fun `a form that owes a reading shows temperature warning when validation is triggered`() {
        render(temperatureState(listOf("", "")).copy(showValidation = true))

        displayed(copy(R.string.report_temperatures_required))
    }

    @Test
    fun `a form with every reading filled says nothing and offers the save`() {
        var submitted = 0
        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = temperatureState(listOf("4,5", "-18")),
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
                    onNotesChange = {},
                    onSubmit = { submitted++ },
                    onOpenHistory = {},
                    onRetry = {},
                    onSyncErp = {},
                )
            }
        }

        // The sentence is not drawn, because there is nothing for it to be about, and the save it was
        // withholding is live again.
        composeRule.onNodeWithText(copy(R.string.report_temperatures_required)).assertDoesNotExist()
        composeRule.onNodeWithText(copy(R.string.report_submit))
            .performScrollTo()
            .assertIsEnabled()
            .performClick()

        assertEquals(1, submitted)
    }

    /**
     * S3, at the seam the screen can be asked about it: a reading the operator typed is drawn again when the
     * list comes back to it, because the text is the state's and never the field's.
     *
     * The fixture is several windows tall on purpose, so the scroll below really moves: the last category is
     * what the list draws at the end, several screens away from the temperature the operator filled in, and
     * the way back re-draws that field from the form. What is asserted is the three steps -- the reading on
     * screen, the list away from it (the last category drawn, which is what proves the move), and the reading
     * on screen again -- and what is deliberately not asserted is that the list *disposed* the item: whether a
     * node survives out of the viewport is the list's decision and not the operator's problem, while the claim
     * that matters is that no copy of the text lives in the composition. `TemperatureBlock` holds none -- it
     * reads `readings` from the form and writes back through `onChange` -- which is the same seam the app has:
     * there the state is the view model's, and the view model outlives this composition.
     */
    @Test
    fun `a reading typed into the form comes back after the field scrolls away`() {
        val tall = listOf(category(id = 2, namePt = "Temperaturas", tasks = listOf(coldTask()))) +
            (1..20).map { index ->
                category(
                    id = 100 + index,
                    namePt = "Higiene $index",
                    // Four checks each, because the point of the fixture is its height: a category of one row is
                    // about forty dp, and twenty of those barely outrun the window -- the list scrolls a couple
                    // of hundred pixels and the category under test is still composed just above the viewport.
                    // At four rows each the content is several windows long, which is what makes the scroll
                    // below dispose it for real.
                    tasks = (1..4).map { row ->
                        task(
                            id = 200 + index * 10 + row,
                            categoryId = 100 + index,
                            namePt = "Tarefa $index.$row",
                        )
                    },
                )
            }
        // The state the screen draws is the tall one: the fixture is what puts the temperature category far
        // enough for the scroll below to take it out of the composition, and a state built from the small
        // fixture instead would leave that scroll with nothing to move.
        val liveState = mutableStateOf(state(tall).copy(temperatures = mapOf(5 to listOf("", ""))))

        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = liveState.value,
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { taskId, index, value ->
                        val readings = liveState.value.temperatures[taskId].orEmpty().toMutableList()
                        readings[index] = value
                        liveState.value = liveState.value.copy(
                            temperatures = liveState.value.temperatures + (taskId to readings),
                        )
                    },
                    onNotesChange = {},
                    onSubmit = {},
                    onOpenHistory = {},
                    onRetry = {},
                    onSyncErp = {},
                )
            }
        }

        composeRule.onNodeWithText("Leitura 1").performScrollTo().performTextInput("4,5")
        composeRule.onNodeWithText("4,5").assertIsDisplayed()

        // The list's own items: the history action is index 0, the categories run 1..size, and the last of
        // them is where the scroll below goes.
        val list = composeRule.onAllNodes(hasScrollAction()).onFirst()
        list.performScrollToIndex(tall.size)

        // Away: the last category is drawn now and the reading is a long way above it, so what is on screen
        // is not the field. The last category is asserted rather than the field's absence: the move is the
        // fact, and a node the list kept around out of the viewport says nothing about where the operator is.
        composeRule.onNodeWithText("Tarefa 20.4").assertIsDisplayed()

        // And back, with the reading the operator typed still in it.
        list.performScrollToIndex(1)
        composeRule.onNodeWithText("4,5").assertIsDisplayed()
    }

    /**
     * The sign action, and why the field needs one: `KeyboardType.Decimal` is Android's unsigned decimal
     * input type, so the numeric keypad an operator gets has no minus key while a cold chamber is read below
     * zero.
     *
     * Both directions are asserted because the action is a toggle rather than a one-way switch: a mis-tap on
     * a reading has to be undoable from the same place it happened, and a control that only ever prepended a
     * sign would leave the operator deleting the character by hand.
     */
    @Test
    fun `the sign action makes a reading negative, and takes it back`() {
        val liveState = mutableStateOf(temperatureState(listOf("4,5", "")))

        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = liveState.value,
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { taskId, index, value ->
                        val readings = liveState.value.temperatures[taskId].orEmpty().toMutableList()
                        readings[index] = value
                        liveState.value = liveState.value.copy(
                            temperatures = liveState.value.temperatures + (taskId to readings),
                        )
                    },
                    onNotesChange = {},
                    onSubmit = {},
                    onOpenHistory = {},
                    onRetry = {},
                    onSyncErp = {},
                )
            }
        }

        composeRule.onAllNodesWithText("±").onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("-4,5").assertIsDisplayed()

        composeRule.onAllNodesWithText("±").onFirst().performScrollTo().performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("4,5").assertIsDisplayed()
    }

    @Test
    fun `the screen renders shift selector and allows selecting shift`() {
        var selectedTurno = ""
        composeRule.setContent {
            TrindadeTheme {
                ReportGeneratorScreen(
                    state = state(listOf(category(id = 8, namePt = "Higiene"))),
                    onCheckChange = { _, _ -> },
                    onProductToggle = { _, _, _ -> },
                    onTemperatureChange = { _, _, _ -> },
                    onNotesChange = {},
                    onSubmit = {},
                    onOpenHistory = {},
                    onRetry = {},
                    onTurnoChange = { selectedTurno = it },
                )
            }
        }

        displayed("Turno")
        displayed("🌅 Tarde")
        displayed("🌙 Noite")

        composeRule.onNodeWithText("🌙 Noite").performClick()
        assertEquals("noite", selectedTurno)
    }
}

