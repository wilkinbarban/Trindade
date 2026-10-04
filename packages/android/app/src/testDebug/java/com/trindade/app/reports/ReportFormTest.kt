package com.trindade.app.reports

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner
import com.trindade.app.ui.theme.TrindadeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The report form, rendered rather than read.
 *
 * The form is rendered directly, with data built by hand and no ViewModel and no Hilt, which is exactly
 * what its `ReportFormState` is for: the answers live apart from either screen's `UiState`, so the thing
 * under test is the form and not the generator. That is also the only way this lane can reach the claims
 * that matter here, because the defect it was written for is a form that drew one level of a two-level
 * tree. Nothing in a JVM test of the view model can see what a screen draws, and a form that silently
 * omits a child category is not a cosmetic loss: the edit surface sends the whole `items` array back, so
 * a task this form never drew is a task the next save deletes.
 *
 * Four things about the file are deliberate, and the first three are the lane's own conventions:
 *
 *  * `GraphicsMode.NATIVE` is what makes the rendering real: text is measured by the framework's layout
 *    instead of by a shadow that returns a constant. Every claim below is about a node existing and being
 *    displayed, and a node's size is what "displayed" turns on.
 *  * The form is wrapped in a `verticalScroll` column, so `performScrollTo` always has a scrollable
 *    ancestor and every selection below can be scrolled to before it is asserted or tapped. A node
 *    outside the viewport is not displayed and cannot be tapped -- a tap is delivered at the node's own
 *    position.
 *  * The labels are the ones the app ships for its task types ("Leitura 1") and the ones this test hands
 *    the form as `namePt`, so a selector cannot drift from the copy: an element found by a name the test
 *    invented would pass while the form drew the wrong thing.
 *  * The check boxes are found by `isToggleable()` rather than by their labels, because in a `CheckRow`
 *    the label and the box are siblings and only the box carries the state. The parent's task is drawn
 *    before its child's, so index 0 of that collection is the parent's box, and the assertion that follows
 *    is on the task id rather than on the value alone -- a tap that reached the child's box would carry
 *    the wrong id and fail.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w400dp-h1000dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReportFormTest {

    @get:Rule
    val composeRule = createComposeRule()

    // ---- The data the form is handed, built from the generated contract types ----

    /**
     * One category of the contract, with both names set to the same string.
     *
     * The form draws `namePt` -- the app has no language switch yet -- so a test that reads Portuguese
     * copy is reading what the screen draws, and setting `nameEs` to the same string keeps that honest
     * rather than leaving a second name that would hide a wrong pick.
     *
     * `categoryType` is a real field of the contract and is not what the form uses to decide anything (the
     * tasks carry their own types); it defaults to `check` and is passed where a category is about
     * something else, so the data looks like the endpoint's rather than like a minimal fixture.
     */
    private fun category(
        id: Int,
        namePt: String,
        parentCategoryId: Int? = null,
        categoryType: CategoriesResponseCategoriesInner.CategoryType =
            CategoriesResponseCategoriesInner.CategoryType.check,
        tasks: List<ReportCategoryTasksInner> = emptyList(),
    ) = CategoriesResponseCategoriesInner(
        id = id,
        parentCategoryId = parentCategoryId,
        namePt = namePt,
        nameEs = namePt,
        sortOrder = id,
        categoryType = categoryType,
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

    /** The offers the server answers with, which are what the two product tasks draw their chips from. */
    private fun offers(assai: List<ProductsResponse.Assai>, normal: List<ProductsResponse.Normal>) =
        ProductsResponse(assai = assai, normal = normal)

    // ---- Rendering ----

    /**
     * The form, as the generator draws it: one root category, its children beside it, and its own state.
     *
     * The scroll container is the test's, not the form's, and every callback defaults to a no-op so a
     * test that does not care about one cannot be affected by it.
     */
    private fun render(
        category: CategoriesResponseCategoriesInner,
        children: List<CategoriesResponseCategoriesInner> = emptyList(),
        form: ReportFormState = ReportFormState(),
        readOnly: Boolean = false,
        onCheckChange: (Int, Boolean) -> Unit = { _, _ -> },
        onProductToggle: (Int, String, Boolean) -> Unit = { _, _, _ -> },
        onTemperatureChange: (Int, Int, String) -> Unit = { _, _, _ -> },
    ) {
        composeRule.setContent {
            TrindadeTheme {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CategoryForm(
                        category = category,
                        children = children,
                        form = form,
                        readOnly = readOnly,
                        onCheckChange = onCheckChange,
                        onProductToggle = onProductToggle,
                        onTemperatureChange = onTemperatureChange,
                    )
                }
            }
        }
    }

    /** One label, asserted as drawn after being scrolled to. */
    private fun displayed(text: String) {
        composeRule.onNodeWithText(text).performScrollTo().assertIsDisplayed()
    }

    // ---- The tests ----

    /**
     * The defect this lane exists for. The endpoint serves every active category and orders a child right
     * after its parent, and the generator used to draw the roots only: ids 9 `Caixas pequenas` and 10
     * `Caixas Assai` are active children of category 8 in production and never reached the screen.
     *
     * The child's own name is asserted as well as its task's, because those are the two rows that were
     * missing: a form that drew the child's tasks under the parent's heading would still be able to
     * submit them, but it would be answering under the wrong shelf.
     */
    @Test
    fun `a child category and its task are drawn beside their parent`() {
        val parentTask = task(
            id = 11,
            categoryId = 8,
            namePt = "Lavar as mãos",
            taskType = ReportCategoryTasksInner.TaskType.check,
        )
        val childTask = task(
            id = 12,
            categoryId = 9,
            namePt = "Conferir validade",
            taskType = ReportCategoryTasksInner.TaskType.check,
        )

        render(
            category = category(id = 8, namePt = "Higiene", tasks = listOf(parentTask)),
            children = listOf(
                category(
                    id = 9,
                    namePt = "Caixas pequenas",
                    parentCategoryId = 8,
                    tasks = listOf(childTask),
                ),
            ),
        )

        displayed("Higiene")
        displayed(parentTask.namePt)
        displayed("Caixas pequenas")
        displayed(childTask.namePt)
    }

    /**
     * The three element types that are not a plain check, each drawn from the form state rather than from
     * anything the form holds of its own.
     *
     * The two product tasks are both here, with different offers, because a form that rendered one list
     * for both would look right in either test alone. The temperature task declares two readings and the
     * form is seeded with two readings for it the way the view model seeds them on load -- the form draws
     * a field per entry it is given, so `Leitura 3` not existing is what makes "one field per declared
     * reading" a claim rather than a coincidence of two fields being enough.
     */
    @Test
    fun `each task type is drawn from the offers and the readings the form holds`() {
        val assaiTask = task(
            id = 21,
            categoryId = 3,
            namePt = "Produtos Assaí",
            taskType = ReportCategoryTasksInner.TaskType.check_assai,
        )
        val normalTask = task(
            id = 22,
            categoryId = 3,
            namePt = "Produtos normais",
            taskType = ReportCategoryTasksInner.TaskType.check_normal,
        )
        val temperatureTask = task(
            id = 23,
            categoryId = 3,
            namePt = "Câmara fria",
            taskType = ReportCategoryTasksInner.TaskType.temperature,
            temperatureReadings = 2,
        )

        render(
            category = category(
                id = 3,
                namePt = "Temperaturas",
                categoryType = CategoriesResponseCategoriesInner.CategoryType.temperature,
                tasks = listOf(assaiTask, normalTask, temperatureTask),
            ),
            form = ReportFormState(
                temperatures = mapOf(temperatureTask.id to List(temperatureTask.temperatureReadings) { "" }),
                offers = offers(
                    assai = listOf(ProductsResponse.Assai.Quadrada, ProductsResponse.Assai.Rolo_500),
                    normal = listOf(ProductsResponse.Normal.Nhoque_400g),
                ),
            ),
        )

        displayed(assaiTask.namePt)
        displayed(ProductsResponse.Assai.Quadrada.value)
        displayed(ProductsResponse.Assai.Rolo_500.value)

        displayed(normalTask.namePt)
        displayed(ProductsResponse.Normal.Nhoque_400g.value)

        displayed(temperatureTask.namePt)
        displayed("Leitura 1")
        displayed("Leitura 2")
        composeRule.onNodeWithText("Leitura 3").assertDoesNotExist()
    }

    /**
     * The tap reaches the callback, and it reports the task the box belongs to rather than just the value.
     *
     * The parent and the child each have a check task, so there are two boxes and the count is asserted
     * first: the claim is that the parent's box reports the parent's id, and the child's box is what makes
     * that an assertion about which one was tapped.
     */
    @Test
    fun `tapping a check box reports the task it belongs to and the value it now holds`() {
        val parentTask = task(
            id = 31,
            categoryId = 1,
            namePt = "Lavar as mãos",
            taskType = ReportCategoryTasksInner.TaskType.check,
        )
        val childTask = task(
            id = 32,
            categoryId = 2,
            namePt = "Conferir validade",
            taskType = ReportCategoryTasksInner.TaskType.check,
        )
        val reported = mutableListOf<Pair<Int, Boolean>>()

        render(
            category = category(id = 1, namePt = "Higiene", tasks = listOf(parentTask)),
            children = listOf(
                category(
                    id = 2,
                    namePt = "Caixas pequenas",
                    parentCategoryId = 1,
                    tasks = listOf(childTask),
                ),
            ),
            onCheckChange = { taskId, checked -> reported += taskId to checked },
        )

        val boxes = composeRule.onAllNodes(isToggleable())
        boxes.assertCountEquals(2)

        boxes[0].performScrollTo().performClick()
        composeRule.waitForIdle()

        assertEquals(listOf(parentTask.id to true), reported)
    }

    /**
     * Read-only is the server's answer rendered, so the controls have to look and behave like the answer:
     * a box that still toggles would send a check the report does not have, and a field that still accepts
     * text would send a reading that replaced a real one.
     *
     * The tap is asserted rather than assumed. `enabled` on a control is a promise about the callback, and
     * this is the assertion that holds it to that promise -- a form that drew a greyed box and still
     * forwarded the click would pass an "is it disabled" test and fail here.
     */
    @Test
    fun `read-only disables the check box and the temperature field, and a tap reports nothing`() {
        val checkTask = task(
            id = 41,
            categoryId = 4,
            namePt = "Lavar as mãos",
            taskType = ReportCategoryTasksInner.TaskType.check,
        )
        val temperatureTask = task(
            id = 42,
            categoryId = 4,
            namePt = "Câmara fria",
            taskType = ReportCategoryTasksInner.TaskType.temperature,
            temperatureReadings = 2,
        )
        val reported = mutableListOf<Pair<Int, Boolean>>()

        render(
            category = category(
                id = 4,
                namePt = "Recepção",
                tasks = listOf(checkTask, temperatureTask),
            ),
            form = ReportFormState(
                temperatures = mapOf(temperatureTask.id to List(temperatureTask.temperatureReadings) { "" }),
            ),
            readOnly = true,
            onCheckChange = { taskId, checked -> reported += taskId to checked },
        )

        // One check task, so the single toggleable node is the box under test.
        val box = composeRule.onNode(isToggleable())
        box.performScrollTo().assertIsNotEnabled()
        box.performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Pair<Int, Boolean>>(), reported)

        // The field is looked up by its label, which the text field merges into its own node: that is the
        // node that carries whether it can be typed in.
        composeRule.onNodeWithText("Leitura 1").performScrollTo().assertIsNotEnabled()
    }

    /**
     * The chips' own read-only guard, which the test above does not reach: it renders a check task and a
     * temperature task, and `ProductCheckBlock` has a guard of its own (`if (readOnly) return@FilterChip`
     * beside `enabled = !readOnly`). A chip is the third kind of control the form draws, so it is the
     * third that can send an edit the report does not have, and neither half of that guard is proved by
     * a form with no product task in it.
     *
     * It is a sibling test rather than more data in the test above because that test's own comment turns
     * on its data: one check task, so the single toggleable node is the box under test. A product task
     * there would leave that claim standing on the chip not being toggleable -- true today, and a reason
     * for the read-only claim to change without anyone noticing if it stopped being.
     *
     * Both halves are in this one test because each is what the other cannot say. `assertIsNotEnabled()`
     * alone passes on a chip that was never able to report anything, and a toggle in some other test
     * could agree by accident; here the same chip is drawn twice -- read-only, and editable beside it --
     * so the count of what was reported is what separates "the disabled chip reported nothing" from
     * "the chip does not work". The two forms share one fixture and one callback, so the only difference
     * between the chips is the `readOnly` argument.
     *
     * The read-only form is composed first and the editable one second, which is what makes the two chips
     * of an identical label index 0 and index 1 of the collection below rather than an order to be
     * discovered by reading which one is enabled.
     *
     * Each chip is scrolled to before it is tapped, and the read-only one is scrolled back to after the
     * editable one has been: a tap is delivered at the node's own position, so the chip that has to
     * report nothing is put back on screen first, and the assertion below is about a disabled chip that
     * was tapped rather than about a chip that was never reached.
     */
    @Test
    fun `a product chip is not enabled in read-only, and the same chip reports a toggle when it is not`() {
        val assaiTask = task(
            id = 51,
            categoryId = 5,
            namePt = "Produtos Assaí",
            taskType = ReportCategoryTasksInner.TaskType.check_assai,
        )
        val product = ProductsResponse.Assai.Quadrada.value
        val productCategory = category(id = 5, namePt = "Recebimento", tasks = listOf(assaiTask))
        val form = ReportFormState(
            offers = offers(assai = listOf(ProductsResponse.Assai.Quadrada), normal = emptyList()),
        )
        val reported = mutableListOf<Triple<Int, String, Boolean>>()
        val onProductToggle = { taskId: Int, name: String, chosen: Boolean ->
            reported += Triple(taskId, name, chosen)
        }

        composeRule.setContent {
            TrindadeTheme {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    CategoryForm(
                        category = productCategory,
                        children = emptyList(),
                        form = form,
                        readOnly = true,
                        onCheckChange = { _, _ -> },
                        onProductToggle = onProductToggle,
                        onTemperatureChange = { _, _, _ -> },
                    )
                    CategoryForm(
                        category = productCategory,
                        children = emptyList(),
                        form = form,
                        readOnly = false,
                        onCheckChange = { _, _ -> },
                        onProductToggle = onProductToggle,
                        onTemperatureChange = { _, _, _ -> },
                    )
                }
            }
        }

        // A chip's label is merged into the chip's own node, so each form contributes exactly one node
        // for the product -- one read-only and one editable, in that order.
        val chips = composeRule.onAllNodesWithText(product)
        chips.assertCountEquals(2)

        chips[0].performScrollTo().assertIsNotEnabled()
        chips[1].performScrollTo().assertIsEnabled()

        // Back to the read-only chip before tapping it, and asserted as displayed there. A tap is
        // delivered at the node's own position, so a chip the scroll above left off screen is a tap that
        // never arrives -- and the assertion below would then be about a chip nobody tapped rather than
        // about a disabled chip that reported nothing.
        chips[0].performScrollTo().assertIsDisplayed()

        // The tap itself is asserted rather than assumed, and it is the whole point of a disabled chip:
        // a form that drew a greyed chip and still forwarded the click would pass an "is it disabled"
        // test and fail here.
        chips[0].performClick()
        composeRule.waitForIdle()

        assertEquals(emptyList<Triple<Int, String, Boolean>>(), reported)

        chips[1].performClick()
        composeRule.waitForIdle()

        // The chip starts unselected, so the toggle it reports is the selection being made, and the task
        // id is the one the chip belongs to rather than whichever one was drawn.
        assertEquals(listOf(Triple(assaiTask.id, product, true)), reported)
    }
}
