package com.trindade.app.reports

import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.CreateReportRequest
import com.trindade.app.contract.models.ReportCategoryTasksInner
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Every reading the fake's temperature task declares, filled with a number.
     *
     * The form withholds the save until it owes nothing -- the tests further down are about that rule -- so
     * a test about what a submission carries has to fill the whole set first: what it asserts is the body,
     * and the body only exists once the readings do.
     */
    private fun fillTemperatures(model: ReportGeneratorViewModel, value: String = "-18") {
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 0, value = value)
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 1, value = value)
    }

    @Test
    fun `submit with missing temperatures triggers validation without sending body`() {
        val model = viewModel()
        assertFalse(model.state.value.showValidation)

        model.submit()

        assertTrue(model.state.value.showValidation)
        assertNull(api.createdBody)
    }

    @Test
    fun `onReportCreatedHandled clears createdReportId, form inputs, and validation`() {
        val model = viewModel()
        fillTemperatures(model)
        model.onNotesChange("Note to clear")
        model.submit()
        assertEquals(42, model.state.value.createdReportId)

        model.onReportCreatedHandled()
        assertNull(model.state.value.createdReportId)
        assertEquals("", model.state.value.notes)
        assertFalse(model.state.value.showValidation)
    }

    @Test
    fun `selectTurno manually overrides detected shift and clears missing prerequisite`() {
        val model = viewModel(FakeReportsApi(turnoValue = null))
        assertEquals(listOf(ReportGeneratorViewModel.Prerequisite.SHIFT), model.state.value.missing)
        assertNull(model.state.value.turno)

        model.selectTurno("noite")
        assertEquals("noite", model.state.value.turno)
        assertFalse(model.state.value.missing.contains(ReportGeneratorViewModel.Prerequisite.SHIFT))

        model.selectTurno("tarde")
        assertEquals("tarde", model.state.value.turno)
    }

    @Test
    fun `submitting uses the manually selected shift instead of the initially detected one`() {
        val model = viewModel()
        fillTemperatures(model)
        model.selectTurno("noite")
        model.submit()

        assertEquals(CreateReportRequest.Turno.noite, api.createdBody!!.turno)
    }

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
        // The task declares two readings and the form owes both, so the second one is filled too: there is
        // no body to read until the set is complete, and this test is about what the body carries.
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 1, value = "-18")
        model.submit()

        val readings = api.createdBody!!.temperatures!!
        val first = readings.single { it.readingIndex == 1 }
        // A Brazilian operator types 4,5 and toDoubleOrNull on that string returns null, so the
        // reading would be dropped and the server would blame a missing temperature.
        assertEquals(BigDecimal("4.5"), first.value)
        // The location is the task's name, not a free-text field this client invents.
        assertEquals(TEMPERATURE_TASK_NAME, first.location)
        // One-based, because the server accepts 1 to 3.
        assertEquals(1, first.readingIndex)
        // And the second declared reading goes out beside it, at its own index.
        assertEquals(BigDecimal("-18"), readings.single { it.readingIndex == 2 }.value)
    }

    @Test
    fun `drops an empty reading rather than sending a zero`() {
        // Asked of the body builder directly, because a blank reading no longer reaches `submit()`: the form
        // withholds the save until every declared reading parses, so a test is the only caller that can hand
        // this function a blank. The rule still matters on the wire -- sending zero for a blank field would
        // store a temperature nobody measured -- and leaving the reading out is what lets the server say
        // which one is actually missing.
        val readings = reportTemperatures(
            FakeReportsApi.defaultCategories().flatMap { it.tasks },
            mapOf(TEMPERATURE_TASK_ID to listOf("4,5", "   ")),
            String::toReading,
        )

        assertEquals(1, readings.size)
        assertEquals(BigDecimal("4.5"), readings.single().value)
        assertEquals(1, readings.single().readingIndex)
    }

    @Test
    fun `sends checks and selected products, and no temperature task as an item`() {
        val model = viewModel()
        fillTemperatures(model)
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
        fillTemperatures(model)
        model.submit()

        assertEquals(42, model.state.value.createdReportId)
        assertEquals(false, model.state.value.canSubmit)
    }

    @Test
    fun `says a report already exists when the server answers 409, instead of a generic failure`() {
        val api = FakeReportsApi(createResponse = Response.error(409, EMPTY_BODY))
        val model = viewModel(api)
        fillTemperatures(model)
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
        // Ready is about the read; "can be sent" is about the answers too, so the readings the form owes are
        // filled before the flag is read. The tests below pin the other direction of the same flag.
        fillTemperatures(model)
        assertEquals(true, model.state.value.canSubmit)
    }

    @Test
    fun `retrying after a failure keeps what the operator already answered`() {
        val api = FakeReportsApi()
        val model = viewModel(api)
        model.onCheckChange(CHECK_TASK_ID, true)
        model.onTemperatureChange(TEMPERATURE_TASK_ID, 0, "12")
        model.onTemperatureChange(TEMPERATURE_TASK_ID, 1, "-18")

        // The next read fails whole, and what the operator typed is not the read's business.
        api.categories = null
        model.load()
        assertEquals(ReportGeneratorViewModel.Stage.FAILED, model.state.value.stage)
        assertEquals(listOf(ReportGeneratorViewModel.Prerequisite.CATEGORIES), model.state.value.missing)
        assertEquals(true, model.state.value.checks[CHECK_TASK_ID])
        assertEquals(listOf("12", "-18"), model.state.value.temperatures[TEMPERATURE_TASK_ID])

        // And the retry that succeeds leaves them where they were, including for the task the recovered read
        // lists again: the seed fills what has no answer and never replaces one.
        api.categories = FakeReportsApi.defaultCategories()
        model.load()
        assertEquals(ReportGeneratorViewModel.Stage.READY, model.state.value.stage)
        assertEquals(true, model.state.value.checks[CHECK_TASK_ID])
        assertEquals(listOf("12", "-18"), model.state.value.temperatures[TEMPERATURE_TASK_ID])
        // The state the form is read from is the state the fields draw, so the readings surviving the read is
        // what "a scroll does not lose the operator's work" means: nothing about the answers lives in the
        // composition.
        assertEquals(true, model.state.value.canSubmit)
    }

    // ---- The readings the form owes before it may send anything ----

    /**
     * The rule this group exists for: a form that owes a reading is not one to send.
     *
     * The failure it closes is the one the operator lived through. The save was drawn live, the body left
     * with the blank readings dropped, and the server answered 400 "Falta colocar as temperaturas no
     * relatório." about a form the operator had no way to know was incomplete -- the sentence names the
     * temperature as a whole, and the fields it is about look like every other empty field on the screen.
     * Withholding the save and naming the slots is what turns that round trip into something the operator
     * can act on before pressing anything.
     */
    @Test
    fun `a fresh form owes both readings its temperature task declares, and sends nothing`() {
        val model = viewModel()

        assertEquals(
            setOf(TemperatureSlot(TEMPERATURE_TASK_ID, 0), TemperatureSlot(TEMPERATURE_TASK_ID, 1)),
            model.state.value.missingReadings,
        )
        assertEquals(false, model.state.value.canSubmit)

        // The write is not attempted at all: no body, and no request the server would refuse.
        model.submit()
        assertNull(api.createdBody)
    }

    @Test
    fun `typing one of the two readings leaves only the other one owed`() {
        val model = viewModel()
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 0, value = "4,5")

        assertEquals(setOf(TemperatureSlot(TEMPERATURE_TASK_ID, 1)), model.state.value.missingReadings)
        assertEquals(false, model.state.value.canSubmit)
    }

    @Test
    fun `a reading that is not a number is owed like a blank one`() {
        val model = viewModel()
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 0, value = "abc")
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 1, value = "-18")

        assertEquals(setOf(TemperatureSlot(TEMPERATURE_TASK_ID, 0)), model.state.value.missingReadings)
        assertEquals(false, model.state.value.canSubmit)
    }

    @Test
    fun `filling the whole set is what lets the form send`() {
        val model = viewModel()
        fillTemperatures(model)

        assertEquals(emptySet<TemperatureSlot>(), model.state.value.missingReadings)
        assertEquals(true, model.state.value.canSubmit)
    }

    @Test
    fun `a negative reading typed with a comma reaches the body as a negative decimal`() {
        val model = viewModel()
        model.onTemperatureChange(TEMPERATURE_TASK_ID, 0, "-2,4")
        model.onTemperatureChange(TEMPERATURE_TASK_ID, 1, "-18")
        model.submit()

        // A cold chamber is read below zero. The parser both surfaces share carries the sign the way it carries
        // the comma, so `-2,4` is the reading −2,4 and not one the form would call missing.
        assertEquals(BigDecimal("-2.4"), api.createdBody!!.temperatures!!.single { it.readingIndex == 1 }.value)
    }

    // ---- What a refusal says (S6) ----

    /**
     * The misreporting this group closes: every 400 used to be answered with "fill in the temperatures".
     *
     * The payload has more than one way to be refused -- a report already filed for the shift and day, a
     * constraint the transaction refused, a reading set the server counted as incomplete -- and the server
     * resolves its codes into the operator's own language before answering, so the sentence to say is the one
     * that arrived with the refusal rather than this client's guess at it.
     */
    @Test
    fun `a 400 shows the sentence the server sent instead of guessing at temperatures`() {
        val duplicate = "Já existe um relatório para este turno hoje."
        val api = FakeReportsApi(createResponse = Response.error(400, jsonBody(duplicate)))
        val model = viewModel(api)
        fillTemperatures(model)
        model.submit()

        assertEquals(duplicate, model.state.value.message)
    }

    @Test
    fun `a 400 with no body at all is still reported as an incomplete report`() {
        // The fallback for a refusal that arrived with nothing to read. The one part of the body this client
        // builds and checks is the reading set, so that is the only thing it can still name on its own.
        val api = FakeReportsApi(createResponse = Response.error(400, EMPTY_BODY))
        val model = viewModel(api)
        fillTemperatures(model)
        model.submit()

        assertEquals(true, model.state.value.message!!.contains("incompleto", ignoreCase = true))
    }

    @Test
    fun `a 400 with Invalid input from schema validator is reported as incomplete rather than raw English`() {
        val api = FakeReportsApi(createResponse = Response.error(400, jsonBody("Invalid input")))
        val model = viewModel(api)
        fillTemperatures(model)
        model.submit()

        assertEquals(true, model.state.value.message!!.contains("incompleto", ignoreCase = true))
    }

    /**
     * The production shape, counted in readings and not in tasks.
     *
     * Task 5 declares two readings and tasks 40, 41 and 42 declare one each, which is the set the server's
     * `validateTemperatures` builds and the reason the operator's 400 arrived: a client that counted tasks
     * instead of slots would call a three-filled-task form complete and leave two readings behind. The
     * fixture is the catalogue's own shape rather than the single task the rest of this file uses.
     */
    @Test
    fun `the production catalog owes five readings across its four temperature tasks`() {
        val api = FakeReportsApi().apply { categories = productionTemperatureCategories() }
        val model = viewModel(api)

        assertEquals(
            setOf(5 to 0, 5 to 1, 40 to 0, 41 to 0, 42 to 0),
            model.state.value.missingReadings.map { it.taskId to it.index }.toSet(),
        )
        assertEquals(5, model.state.value.temperatures.values.sumOf { it.size })
        assertEquals(false, model.state.value.canSubmit)

        listOf(5 to 0, 5 to 1, 40 to 0, 41 to 0, 42 to 0).forEach { (taskId, index) ->
            model.onTemperatureChange(taskId, index, "-18")
        }

        assertEquals(true, model.state.value.canSubmit)
    }

    /** The catalogue's temperature category: the four tasks and the five readings the server validates. */
    private fun productionTemperatureCategories() = listOf(
        CategoriesResponseCategoriesInner(
            id = 2,
            parentCategoryId = null,
            namePt = "Temperaturas",
            nameEs = "Temperaturas",
            sortOrder = 2,
            categoryType = CategoriesResponseCategoriesInner.CategoryType.entries.first(),
            tasks = listOf(
                productionTask(id = 5, name = "Câmara Principal", readings = 2),
                productionTask(id = 40, name = "Câmara Nhoque", readings = 1),
                productionTask(id = 41, name = "Câmara Fria 1", readings = 1),
                productionTask(id = 42, name = "Câmara Fria 2", readings = 1),
            ),
        ),
    )

    private fun productionTask(id: Int, name: String, readings: Int) = ReportCategoryTasksInner(
        id = id,
        categoryId = 2,
        namePt = name,
        nameEs = name,
        taskType = ReportCategoryTasksInner.TaskType.temperature,
        temperatureReadings = readings,
    )
}

private val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())

/**
 * The envelope a refusal arrives in, with the same sentence in `error` and `message` as the server sends it.
 *
 * Both fields, because the contract reserves `message` for a localised business sentence while the routes that
 * set it fill `error` with the same text; a client that read only one of them would pass here and fail against
 * a route that used the other.
 */
private fun jsonBody(sentence: String): okhttp3.ResponseBody =
    """{"error":"$sentence","message":"$sentence"}""".toResponseBody("application/json".toMediaType())
