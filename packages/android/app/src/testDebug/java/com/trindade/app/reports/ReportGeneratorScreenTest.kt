package com.trindade.app.reports

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner
import com.trindade.app.ui.theme.TrindadeTheme
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
 *    right after its parent and the first test's child sits between the two roots. That is the order
 *    the screen's filter walks, and a child parked at the end of the list would test the filter less.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportGeneratorScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

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

    /** One task of the contract. `temperatureReadings` is 0 for the three types that have no readings. */
    private fun task(id: Int, categoryId: Int, namePt: String) = ReportCategoryTasksInner(
        id = id,
        categoryId = categoryId,
        namePt = namePt,
        nameEs = namePt,
        taskType = ReportCategoryTasksInner.TaskType.check,
        temperatureReadings = 0,
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
                    onSubmit = {},
                    onOpenHistory = {},
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
     * The same wiring when the children have to be matched to the right root: the child belongs to the
     * first root, and the second root's own task is drawn too.
     *
     * A missing `children` argument is what the test above catches; this one is for a wrong one -- the
     * children looked up with a key that is not the current root's id, the first root's for every root,
     * say. The first half alone cannot separate those two readings, because the child belongs to the
     * first root and is drawn there either way; it is the second root's own task that refuses the wrong
     * one. A lookup that handed every root the first root's children would also draw the child twice,
     * and a selection by label refuses a second match instead of quietly picking one.
     */
    @Test
    fun `the child is drawn with the root it belongs to, not with whichever root comes first`() {
        val childTask = task(id = 22, categoryId = 9, namePt = "Conferir validade")
        val otherRootTask = task(id = 31, categoryId = 20, namePt = "Conferir a geladeira")

        render(
            state(
                listOf(
                    category(id = 8, namePt = "Higiene"),
                    category(
                        id = 9,
                        namePt = "Caixas pequenas",
                        parentCategoryId = 8,
                        tasks = listOf(childTask),
                    ),
                    category(id = 20, namePt = "Recepção", tasks = listOf(otherRootTask)),
                ),
            ),
        )

        displayed(childTask.namePt)
        displayed(otherRootTask.namePt)
    }
}
