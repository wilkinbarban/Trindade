package com.trindade.app.reports

import com.trindade.app.contract.models.ReportResponse
import com.trindade.app.reports.FakeReportsApi.Companion.CHECK_TASK_ID
import com.trindade.app.reports.FakeReportsApi.Companion.PRODUCT_TASK_ID
import com.trindade.app.reports.FakeReportsApi.Companion.TEMPERATURE_TASK_ID
import com.trindade.app.reports.FakeReportsApi.Companion.TEMPERATURE_TASK_NAME
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The generator's state and, more importantly, the payload it builds.
 *
 * The payload is where the assertions earn their place. Every field in it was learned by reading the
 * SPA and the server rather than from the contract: a temperature's `location` is the task's own name,
 * `readingIndex` is one-based, and a reading typed with a comma has to reach the server as a decimal.
 * None of those are visible in a type signature, and all of them are silent when wrong -- the server
 * answers that a temperature is missing and points the operator at the wrong problem.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` runs on Main, which a JVM test has no
 * implementation of until one is installed.
 */
class ReportGeneratorViewModelTest {

    private lateinit var api: FakeReportsApi

    @Before
    fun installMainDispatcher() {
        // Unconfined so the coroutines run as they are launched and the assertions need no manual
        // advancing, which keeps the tests about the state rather than about the scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        api = FakeReportsApi()
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: FakeReportsApi = this.api) = ReportGeneratorViewModel(ReportsRepository(api))

    @Test
    fun `loads the categories, the offers and the shift together`() {
        val model = viewModel()

        val state = model.state.value
        assertEquals(1, state.categories.size)
        assertNotNull(state.offers)
        assertEquals(FakeReportsApi.DEFAULT_TURNO, state.turno)
        assertEquals(false, state.loading)
        assertEquals(true, state.ready)
    }

    @Test
    fun `opens one temperature field per reading the task declares`() {
        val model = viewModel()

        // Not one field to be grown as the operator types: the form has to show how many readings it
        // owes from the start, because an export refuses a report missing one.
        assertEquals(listOf("", ""), model.state.value.temperatures[TEMPERATURE_TASK_ID])
    }

    @Test
    fun `refuses to submit while the shift is unknown`() {
        // The one absence worth refusing outright: the server detects the shift, and a form without it
        // would have to guess, producing a report filed against the wrong one. The refusal is typed rather
        // than a sentence left in the state, and the sentence the operator reads comes from the screen's
        // own resources, which is what the screen's lane asserts.
        val model = viewModel(FakeReportsApi(turnoValue = null))

        assertEquals(false, model.state.value.ready)
        assertEquals(false, model.state.value.canSubmit)
        assertEquals(ReportGeneratorViewModel.Stage.FAILED, model.state.value.stage)
        assertEquals(listOf(ReportGeneratorViewModel.Prerequisite.SHIFT), model.state.value.missing)
    }

    @Test
    fun `sends a reading typed with a comma as a decimal, located at the task's own name`() {
        val model = viewModel()
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 0, value = "4,5")
        model.submit()

        val reading = api.createdBody!!.temperatures!!.single()
        // A Brazilian operator types 4,5 and toDoubleOrNull on that string returns null, so the
        // reading would be dropped and the server would blame a missing temperature.
        assertEquals(BigDecimal("4.5"), reading.value)
        // The location is the task's name, not a free-text field this client invents.
        assertEquals(TEMPERATURE_TASK_NAME, reading.location)
        // One-based, because the server accepts 1 to 3.
        assertEquals(1, reading.readingIndex)
    }

    @Test
    fun `drops an empty reading rather than sending a zero`() {
        val model = viewModel()
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 0, value = "4,5")
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 1, value = "   ")
        model.submit()

        // Sending zero for a blank field would store a temperature nobody measured. Leaving it out lets
        // the server say what is actually missing.
        assertEquals(1, api.createdBody!!.temperatures!!.size)
    }

    @Test
    fun `sends checks and selected products, and no temperature task as an item`() {
        val model = viewModel()
        model.onCheckChange(CHECK_TASK_ID, checked = true)
        model.onProductToggle(PRODUCT_TASK_ID, FakeReportsApi.defaultOffers().assai.first().value, selected = true)
        model.submit()

        val items = api.createdBody!!.items!!
        assertEquals(true, items.first { it.taskId == CHECK_TASK_ID }.checked)
        assertEquals(
            listOf(FakeReportsApi.defaultOffers().assai.first().value),
            items.first { it.taskId == PRODUCT_TASK_ID }.selectedProducts,
        )
        // A temperature task belongs in `temperatures`, not among the items; sending it in both would
        // have the server read one of them as authoritative.
        assertEquals(null, items.firstOrNull { it.taskId == TEMPERATURE_TASK_ID })
    }

    @Test
    fun `reports the id the server created`() {
        val model = viewModel()
        model.submit()

        assertEquals(42, model.state.value.createdReportId)
        assertEquals(false, model.state.value.canSubmit)
    }

    @Test
    fun `says a report already exists when the server answers 409, instead of a generic failure`() {
        val api = FakeReportsApi(createResponse = Response.error(409, EMPTY_BODY))
        val model = viewModel(api)
        model.submit()

        assertNull(model.state.value.createdReportId)
        // Case-insensitive on purpose: pinning the exact wording would make this test fail on a copy
        // edit that broke nothing, and what it is checking is which refusal was recognised.
        assertEquals(true, model.state.value.message!!.contains("existe", ignoreCase = true))
    }
    // ---- The prerequisites, named instead of left as an empty form (AND-02) ----

    /**
     * The rule this group exists for: a read that could not supply something says which something.
     *
     * The failure it closes is quiet and worse than an error message. The screen drew its form from
     * whatever the reads left in the state, so a failed call left an empty collection and the operator saw
     * a blank form with a dead submit button and no sentence explaining either -- a form that looks like a
     * day with no tasks rather than a read that did not arrive.
     */
    @Test
    fun `a read that supplies nothing names the shift, the categories and the offers`() {
        val api = FakeReportsApi(turnoValue = null).apply {
            categories = null
            offers = null
        }
        val model = viewModel(api)

        val state = model.state.value
        assertEquals(ReportGeneratorViewModel.Stage.FAILED, state.stage)
        assertEquals(
            listOf(
                ReportGeneratorViewModel.Prerequisite.SHIFT,
                ReportGeneratorViewModel.Prerequisite.CATEGORIES,
                ReportGeneratorViewModel.Prerequisite.OFFERS,
            ),
            state.missing,
        )
        assertEquals(false, state.canSubmit)
    }

    @Test
    fun `a missing shift refuses the form on its own`() {
        val model = viewModel(FakeReportsApi(turnoValue = null))

        assertEquals(ReportGeneratorViewModel.Stage.FAILED, model.state.value.stage)
        assertEquals(listOf(ReportGeneratorViewModel.Prerequisite.SHIFT), model.state.value.missing)
    }

    @Test
    fun `missing offers leave the form incomplete rather than unusable`() {
        val api = FakeReportsApi().apply { offers = null }
        val model = viewModel(api)

        val state = model.state.value
        assertEquals(ReportGeneratorViewModel.Stage.INCOMPLETE, state.stage)
        assertEquals(listOf(ReportGeneratorViewModel.Prerequisite.OFFERS), state.missing)
        // The sections the server did supply are still there: the form is drawn, it just cannot be sent.
        assertEquals(true, state.categories.isNotEmpty())
        assertEquals(false, state.canSubmit)
    }

    @Test
    fun `a complete read is ready and can be sent`() {
        val model = viewModel()

        assertEquals(ReportGeneratorViewModel.Stage.READY, model.state.value.stage)
        assertEquals(emptyList<ReportGeneratorViewModel.Prerequisite>(), model.state.value.missing)
        assertEquals(true, model.state.value.canSubmit)
    }

    @Test
    fun `retrying after a failure keeps what the operator already answered`() {
        val api = FakeReportsApi()
        val model = viewModel(api)
        model.onCheckChange(CHECK_TASK_ID, true)
        model.onTemperatureChange(TEMPERATURE_TASK_ID, 0, "12")

        // The next read fails whole, and what the operator typed is not the read's business.
        api.categories = null
        model.load()
        assertEquals(ReportGeneratorViewModel.Stage.FAILED, model.state.value.stage)
        assertEquals(listOf(ReportGeneratorViewModel.Prerequisite.CATEGORIES), model.state.value.missing)
        assertEquals(true, model.state.value.checks[CHECK_TASK_ID])
        assertEquals("12", model.state.value.temperatures[TEMPERATURE_TASK_ID]?.first())

        // And the retry that succeeds leaves them where they were, including for the task the recovered read
        // lists again: the seed fills what has no answer and never replaces one.
        api.categories = FakeReportsApi.defaultCategories()
        model.load()
        assertEquals(ReportGeneratorViewModel.Stage.READY, model.state.value.stage)
        assertEquals(true, model.state.value.checks[CHECK_TASK_ID])
        assertEquals("12", model.state.value.temperatures[TEMPERATURE_TASK_ID]?.first())
    }
}

private val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())
