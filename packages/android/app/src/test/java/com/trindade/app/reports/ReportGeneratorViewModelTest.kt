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
        // would have to guess, producing a report filed against the wrong one.
        val model = viewModel(FakeReportsApi(turnoValue = null))

        assertEquals(false, model.state.value.ready)
        assertEquals(false, model.state.value.canSubmit)
        assertNotNull("the operator has to be told why", model.state.value.message)
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
}

private val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())
