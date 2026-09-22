package com.trindade.app.reports

import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportDetailItemsInner
import com.trindade.app.contract.models.ReportDetailTemperaturesInner
import com.trindade.app.contract.models.ReportResponse
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.contract.models.UpdateReportRequest
import com.trindade.app.network.ReportsApi
import com.trindade.app.reports.FakeReportsApi.Companion.CHECK_TASK_ID
import com.trindade.app.reports.FakeReportsApi.Companion.PRODUCT_TASK_ID
import com.trindade.app.reports.FakeReportsApi.Companion.TEMPERATURE_TASK_ID
import com.trindade.app.reports.FakeReportsApi.Companion.TEMPERATURE_TASK_NAME
import java.io.IOException
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The edit screen's state: what it seeds from a stored report, and what it sends back.
 *
 * The seeding is the half that has no counterpart in the generator, and it is the half a type signature
 * cannot describe: a report carries its temperatures flat -- `location`, `readingIndex`, `value` -- while
 * the form wants one list per task, so the location has to become a task id. Nothing about that mapping
 * is visible in either DTO, and getting it wrong is silent in the worst way: the readings do not appear,
 * the operator fills blanks over values that were already stored, and the server has no way to know.
 *
 * `readOnly` is the other claim worth a test. Both flags are the server's, the expression is the SPA's,
 * and the direction that matters is the failing one: a report the server did not mark editable is drawn
 * read-only, so an absent flag can only ever offer less.
 *
 * `Dispatchers.setMain` is required because `viewModelScope` runs on Main, which a JVM test has no
 * implementation of until one is installed.
 */
class ReportEditViewModelTest {

    @Before
    fun installMainDispatcher() {
        // Unconfined so the coroutines run as they are launched and the assertions need no manual
        // advancing, which keeps the tests about the state rather than about the scheduler.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun restoreMainDispatcher() {
        Dispatchers.resetMain()
    }

    private fun viewModel(api: ReportsApi) = ReportEditViewModel(ReportsRepository(api))

    // ---- The data a stored report is built from ----

    /**
     * The fake every test starts from: a report that loads, and the two offers the fixture's product
     * names come from.
     *
     * The offers are explicit rather than the fake's default pair because the report below was stored
     * when these two were on offer, and a fixture whose selected product is not in its own list is a
     * state the server cannot produce.
     */
    private fun fake(
        report: ReportResponseReport = storedReport(),
        updateResponse: Response<ReportResponse> = Response.success(ReportResponse(report = FakeReportsApi.createdReport())),
        updateFailure: Throwable? = null,
    ) = FakeReportsApi(
        offers = ProductsResponse(
            assai = listOf(ProductsResponse.Assai.Quadrada, ProductsResponse.Assai.Rolo_500),
            normal = listOf(ProductsResponse.Normal.Nhoque_400g),
        ),
        reportToReturn = report,
    ).let { RecordingReportsApi(it, updateResponse, updateFailure) }

    /**
     * One stored report, on top of the fake's own created one so the fields this test does not care
     * about stay the shape the server sends instead of a hand-built near-copy of it.
     *
     * `readOnly` and `canEdit` are passed straight through, including as null, because those two are
     * what the read-only test is about: the server computes them together and the fixtures below can
     * send the combinations it sends.
     */
    private fun storedReport(
        id: Int = REPORT_ID,
        notes: String? = null,
        canEdit: Boolean? = true,
        readOnly: Boolean? = false,
        items: List<ReportDetailItemsInner> = emptyList(),
        temperatures: List<ReportDetailTemperaturesInner> = emptyList(),
    ) = FakeReportsApi.createdReport(id = id).copy(
        notes = notes,
        canEdit = canEdit,
        readOnly = readOnly,
        items = items,
        temperatures = temperatures,
    )

    /** One stored answer. `selectedProducts` is null for the three types that have no products. */
    private fun item(
        taskId: Int,
        checked: Boolean,
        selectedProducts: List<String>? = null,
    ) = ReportDetailItemsInner(
        taskId = taskId,
        taskName = "tarefa $taskId",
        taskNameEs = "tarea $taskId",
        taskType = ReportDetailItemsInner.TaskType.check,
        categoryName = "Higiene",
        categoryNameEs = "Higiene",
        checked = checked,
        selectedProducts = selectedProducts,
    )

    /** One stored reading, as the wire carries it: flat, and located by the task's own name. */
    private fun temperature(location: String, readingIndex: Int, value: String) =
        ReportDetailTemperaturesInner(
            location = location,
            locationEs = null,
            readingIndex = readingIndex,
            value = BigDecimal(value),
        )

    // ---- The tests ----

    /**
     * The whole seeding, on one report that carries all three kinds of answer at once.
     *
     * The temperature task declares two readings and the report has one of them, so the assertion is
     * about a slot that had to be created empty as well as a value that had to arrive: a form seeded
     * only as far as the data goes would show one field here, and the operator would never see the
     * reading the export will refuse the report for.
     */
    @Test
    fun `the form is seeded from the report`() {
        val model = viewModel(
            fake(
                report = storedReport(
                    notes = "Faltou o lacre",
                    items = listOf(
                        item(taskId = CHECK_TASK_ID, checked = true),
                        item(
                            taskId = PRODUCT_TASK_ID,
                            checked = false,
                            selectedProducts = listOf(PRODUCTS.first(), PRODUCTS.last()),
                        ),
                    ),
                    temperatures = listOf(temperature(TEMPERATURE_TASK_NAME, readingIndex = 1, value = "4.5")),
                ),
            ),
        )

        model.load(REPORT_ID)
        val state = model.state.value

        assertEquals(REPORT_ID, state.report?.id)
        assertEquals(mapOf(CHECK_TASK_ID to true, PRODUCT_TASK_ID to false), state.checks)
        assertEquals(PRODUCTS.toSet(), state.selectedProducts[PRODUCT_TASK_ID])
        // The second reading the task declares is a slot the report has no value for, and it lands as
        // the form's own blank rather than as a shorter list.
        assertEquals(listOf("4.5", ""), state.temperatures[TEMPERATURE_TASK_ID])
        assertEquals("Faltou o lacre", state.notes)
        assertEquals(FakeReportsApi.DEFAULT_TURNO, state.turno)
    }

    /**
     * The read-only rule, in the direction that fails closed.
     *
     * Three reports, because one cannot separate the expression from a form that is read-only whatever
     * it is handed: the server marks this report not editable, the server says nothing at all, and the
     * server marks it editable.
     */
    @Test
    fun `a report the server did not mark editable is read-only`() {
        // The window has closed, or this operator is not the creator; either way the server said so.
        val closed = viewModel(fake(report = storedReport(canEdit = false, readOnly = true)))
        closed.load(REPORT_ID)
        assertEquals(true, closed.state.value.readOnly)
        assertEquals(false, closed.state.value.canSubmit)

        // Both flags absent: the report is not explicitly editable, and an absence is read as "no"
        // rather than as permission.
        val silent = viewModel(fake(report = storedReport(canEdit = null, readOnly = null)))
        silent.load(REPORT_ID)
        assertEquals(true, silent.state.value.readOnly)
        assertEquals(false, silent.state.value.canSubmit)

        // And the other side of the same expression, so the two claims above are about the flag and not
        // about a form that never edits anything.
        val open = viewModel(fake(report = storedReport(canEdit = true, readOnly = false)))
        open.load(REPORT_ID)
        assertEquals(false, open.state.value.readOnly)
        assertEquals(true, open.state.value.canSubmit)
    }

    /**
     * What the update carries: the form as it stands, not the report as it was loaded.
     *
     * Every field below is changed before the submission, and every assertion is on a value that only
     * the change could have produced -- the check that was true in the report goes out false, the
     * product that was not selected goes out selected, the reading is retyped with a comma, and the
     * notes and the shift are new. A body built from the loaded report would answer all of them with the
     * old values and there is no other way to tell the two apart.
     */
    @Test
    fun `the update carries the form, not the report that was loaded`() {
        val recording = fake(
            report = storedReport(
                notes = "sem sal",
                items = listOf(
                    item(taskId = CHECK_TASK_ID, checked = true),
                    item(taskId = PRODUCT_TASK_ID, checked = false, selectedProducts = listOf(PRODUCTS.first())),
                ),
                temperatures = listOf(temperature(TEMPERATURE_TASK_NAME, readingIndex = 1, value = "4.5")),
            ),
        )
        val model = viewModel(recording)
        model.load(REPORT_ID)

        model.onCheckChange(CHECK_TASK_ID, checked = false)
        model.onProductToggle(PRODUCT_TASK_ID, PRODUCTS.last(), selected = true)
        model.onTemperatureChange(TEMPERATURE_TASK_ID, index = 0, value = "5,0")
        model.onNotesChange("faltou o lacre")
        model.onTurnoChange("noite")
        model.submit()

        // The id is the loaded report's own, which is the one thing about the target the form does not
        // carry: everything else below is the form's.
        assertEquals(REPORT_ID, recording.updatedId)

        val body = recording.updatedBody!!
        assertEquals(UpdateReportRequest.Turno.noite, body.turno)
        assertEquals("faltou o lacre", body.notes)

        val items = body.items!!
        assertEquals(false, items.first { it.taskId == CHECK_TASK_ID }.checked)
        // As a set: what the chip toggled is which products are selected, and the order they are listed
        // in is the set's own business.
        assertEquals(PRODUCTS.toSet(), items.first { it.taskId == PRODUCT_TASK_ID }.selectedProducts?.toSet())
        // A temperature task belongs in `temperatures` and not among the items, which is the payload's
        // rule and the generator's.
        assertEquals(null, items.firstOrNull { it.taskId == TEMPERATURE_TASK_ID })

        // The comma reaches the server as a decimal through the parser both surfaces share, and the
        // second slot -- still blank -- is dropped rather than sent as a zero, so the index that remains
        // is the one that has a reading.
        val readings = body.temperatures!!
        assertEquals(1, readings.size)
        assertEquals(TEMPERATURE_TASK_NAME, readings.single().location)
        assertEquals(BigDecimal("5.0"), readings.single().value)
        assertEquals(1, readings.single().readingIndex)

        assertEquals(true, model.state.value.saved)
    }

    /**
     * The two failures an update can end in, told apart by what they say.
     *
     * A refusal is a business answer and keeps the server's meaning -- the payload is incomplete -- while
     * an unreachable server refused nothing and gets the app's own sentence. Neither leaves `saved` set:
     * the screen leaves on that flag, and leaving on a write the server did not take would lose the
     * operator's work without saying so.
     */
    @Test
    fun `a refused update says what the server's refusal means, and an unreachable one says the app's`() {
        val refused = viewModel(fake(updateResponse = Response.error(400, EMPTY_BODY)))
        refused.load(REPORT_ID)
        refused.submit()

        assertEquals(false, refused.state.value.saved)
        // Case-insensitive on purpose, the way the generator's own refusal test reads it: what is being
        // checked is which refusal was recognised, not the wording of the copy.
        assertEquals(true, refused.state.value.message!!.contains("incompleto", ignoreCase = true))

        val unreachable = viewModel(fake(updateFailure = IOException("no route to host")))
        unreachable.load(REPORT_ID)
        unreachable.submit()

        assertEquals(false, unreachable.state.value.saved)
        // A fragment rather than the whole sentence, for the same reason: a copy edit that broke nothing
        // should not fail this test.
        assertEquals(true, unreachable.state.value.message!!.contains("conexão", ignoreCase = true))
    }

    /**
     * The two refusals that are not the same refusal, told apart by what they say.
     *
     * A 403 is the edit window and nothing else on this route: `reports.command.service.ts` refuses the
     * PATCH when the permissions fail, and which day and which creator those permissions are about is
     * the server's own rule, so this code is the one refusal no retry can change. The generator's generic
     * "try again" would send the operator around a loop with no exit, and the sentence the detail screen
     * already owns says the true thing about the same report; the assertion below is exact rather than a
     * fragment for that reason, since a second copy of the sentence that drifted from the first would be
     * two answers to one refusal.
     *
     * The 400 leg is what makes the 403 leg mean something. A test that only drove the 403 would pass
     * against a view model that answered the detail screen's sentence to every code, and it would also
     * pass against one that never sent the request at all: the report here is the fixture's editable one,
     * so the refusal really is the server's answer and not this form's own read-only guard withholding the
     * submit. A 400 stays the generator's wording, because the two routes refuse the same body for the
     * same reason.
     *
     * Neither refusal leaves `saved` set, for the reason the test above gives: the screen leaves on that
     * flag, and leaving on a write the server refused would lose the operator's work without saying so.
     */
    @Test
    fun `a refused update says whether the window or something else refused it`() {
        val window = viewModel(fake(updateResponse = Response.error(403, EMPTY_BODY)))
        window.load(REPORT_ID)
        window.submit()

        assertEquals(false, window.state.value.saved)
        assertEquals(OUTSIDE_EDIT_WINDOW, window.state.value.message)

        val payload = viewModel(fake(updateResponse = Response.error(400, EMPTY_BODY)))
        payload.load(REPORT_ID)
        payload.submit()

        assertEquals(false, payload.state.value.saved)
        // The sentence that must not be here, as a difference rather than as a fragment: this is the
        // assertion a 400 answered with the window's own sentence would break.
        assertNotEquals(OUTSIDE_EDIT_WINDOW, payload.state.value.message)
        // And which refusal it is instead, in the generator's own words -- the fragment rather than the
        // whole sentence, the way the test above reads its own 400, so a copy edit that broke no
        // behaviour does not fail this one. The `!!` is also what stops a silent `null` from satisfying
        // the difference above.
        assertEquals(true, payload.state.value.message!!.contains("incompleto", ignoreCase = true))
    }

    /**
     * What the notes field sends when it is cleared, and when the operator types room around a word.
     *
     * One rule, the web's own `notes.trim() || null`, and its two halves fail in different ways that are
     * both invisible from the screen. An untrimmed note stores the spaces nobody typed on purpose, and a
     * cleared field sent as `""` stores a note whose text is empty where the server was told there is no
     * note at all -- a report that reads as answered when the operator left it alone. Each half is a
     * mutation the other submission catches: drop the trim and the second assertion fails with the spaces
     * still in the body, drop the empty check and the first one fails holding `""`.
     *
     * The whitespace in the first case is set rather than left as the loaded fixture's blank, so the
     * assertion is about the trim feeding the empty check and not about a field this test never touched.
     */
    @Test
    fun `a blank note is sent as nothing, and a note with spaces around it is sent trimmed`() {
        val blank = fake()
        val cleared = viewModel(blank)
        cleared.load(REPORT_ID)
        cleared.onNotesChange("   ")
        cleared.submit()

        assertEquals(null, blank.updatedBody!!.notes)

        val spaced = fake()
        val typed = viewModel(spaced)
        typed.load(REPORT_ID)
        typed.onNotesChange("  deixar pronto  ")
        typed.submit()

        assertEquals("deixar pronto", spaced.updatedBody!!.notes)
    }

    private companion object {
        /** The report the fixtures load, distinct from the id the fake's created report carries. */
        const val REPORT_ID = 77

        /**
         * The edit window's refusal, spelled out here because neither constant that holds it is reachable.
         *
         * `ReportEditViewModel.OUTSIDE_EDIT_WINDOW` and `ReportDetailViewModel.OUTSIDE_EDIT_WINDOW` -- the
         * constant this one was borrowed from, per that class's own comment -- both live in a private
         * companion, so the sentence is all that is left of them for a test in this package to compare
         * against. This copy is therefore a second place the sentence is written, and the assertion that
         * uses it is what holds the production pair equal: 403 on a report is one fact about one report,
         * and the two screens that can be looking at it must not describe it in two different ways.
         */
        const val OUTSIDE_EDIT_WINDOW = "Fora do prazo de edição: este relatório é somente leitura."

        /** The two products the product task had selected, in the order the report carries them. */
        val PRODUCTS = listOf(ProductsResponse.Assai.Quadrada.value, ProductsResponse.Assai.Rolo_500.value)
    }
}

/**
 * The update the existing fake declines to answer, recorded instead of thrown away.
 *
 * `FakeReportsApi` already answers everything this file needs to read -- the categories, the offers and
 * the report -- and its `updateReport` throws `NOT_USED`, which no test had needed until now. It is
 * final, and its own file is not one of this task's surfaces, so the recording is layered here instead:
 * interface delegation hands every other call to the same fake, and only the update is replaced. That
 * makes the fake's reads the ones under test rather than a second, local imitation of them, and
 * [updatedBody] is the point of the whole exercise -- the claim is about the body the screen sends, so a
 * double that answered without keeping it could not make the claim at all.
 *
 * [updateFailure] is the unreachable server: the repository turns a thrown call into `Unreachable`, so
 * that is the only way to reach the failure the screen has its own sentence for.
 */
private class RecordingReportsApi(
    private val delegate: FakeReportsApi,
    private val updateResponse: Response<ReportResponse>,
    private val updateFailure: Throwable?,
) : ReportsApi by delegate {

    /** The last update's id and body, which is what the tests assert on. */
    var updatedId: Int? = null
        private set
    var updatedBody: UpdateReportRequest? = null
        private set

    override suspend fun updateReport(id: Int, body: UpdateReportRequest): Response<ReportResponse> {
        updatedId = id
        updatedBody = body
        updateFailure?.let { throw it }
        return updateResponse
    }
}

private val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())
