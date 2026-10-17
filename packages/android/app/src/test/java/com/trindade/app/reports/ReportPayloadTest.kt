package com.trindade.app.reports

import com.trindade.app.contract.models.ReportCategoryTasksInner
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The payload's rules, asked about the way the two forms ask about them.
 *
 * `ReportPayload.kt` is where the rules that turn a filled form into the wire body live, and this file
 * reads them on their own rather than through a view model. That is deliberate for both of the rules it
 * covers. The completeness question -- which readings a form still owes -- is what a form about to send
 * has to answer before anything leaves the phone, and the body builder is what answers what a blank
 * reading costs once the form is allowed to send; the generator and the report's edit screen share both,
 * so a test that went through one of them would be asserting one caller and reading the rule for the
 * other as well. Neither failure is visible in a type signature: a half-filled body is a 400 whose
 * sentence points at the temperature as a whole, and a blank reading sent as a zero is a temperature
 * nobody measured.
 *
 * The fixtures are the shape the server serves -- a task declares how many readings it has -- because the
 * declared count is exactly what both rules count.
 */
class ReportPayloadTest {

    /** One temperature task, with the name the body's `location` has to carry. */
    private fun temperatureTask(
        id: Int = TASK_ID,
        readings: Int = 1,
        name: String = NAME,
    ) = ReportCategoryTasksInner(
        id = id,
        categoryId = CATEGORY_ID,
        namePt = name,
        nameEs = name,
        taskType = ReportCategoryTasksInner.TaskType.temperature,
        temperatureReadings = readings,
    )

    /** One task of another type, which owes no reading at all. */
    private fun checkTask(id: Int = 90) = ReportCategoryTasksInner(
        id = id,
        categoryId = CATEGORY_ID,
        namePt = "Limpeza geral",
        nameEs = "Limpieza general",
        taskType = ReportCategoryTasksInner.TaskType.check,
        temperatureReadings = 0,
    )

    private fun missing(
        tasks: List<ReportCategoryTasksInner>,
        temperatures: Map<Int, List<String>>,
    ) = missingTemperatureReadings(tasks, temperatures, String::toReading)

    // ---- What a form still owes before it may send ----

    @Test
    fun `a form nobody has typed in owes every declared reading`() {
        val owed = missing(listOf(temperatureTask(readings = 2)), mapOf(TASK_ID to listOf("", "")))

        assertEquals(setOf(TemperatureSlot(TASK_ID, 0), TemperatureSlot(TASK_ID, 1)), owed)
    }

    @Test
    fun `a whitespace-only field owes its reading`() {
        // The parser trims, so a field holding spaces is the same field as an empty one. A rule that took
        // the spaces as an answer would let through the very body the server refuses.
        val owed = missing(listOf(temperatureTask()), mapOf(TASK_ID to listOf("   ")))

        assertEquals(setOf(TemperatureSlot(TASK_ID, 0)), owed)
    }

    @Test
    fun `a reading typed with a comma is complete`() {
        val owed = missing(listOf(temperatureTask()), mapOf(TASK_ID to listOf("4,5")))

        assertEquals(emptySet<TemperatureSlot>(), owed)
    }

    @Test
    fun `a field that is not a number owes its reading`() {
        // The body builder would drop `abc` for the same reason it drops a blank, so a rule that only looked
        // for blanks would send a body the server refuses while the operator believed the field was filled.
        val owed = missing(listOf(temperatureTask()), mapOf(TASK_ID to listOf("abc")))

        assertEquals(setOf(TemperatureSlot(TASK_ID, 0)), owed)
    }

    @Test
    fun `a task the form holds no list for owes all of its readings`() {
        val owed = missing(listOf(temperatureTask(readings = 2)), emptyMap())

        assertEquals(setOf(TemperatureSlot(TASK_ID, 0), TemperatureSlot(TASK_ID, 1)), owed)
    }

    @Test
    fun `a list shorter than the task declares owes the readings it never grew to`() {
        val owed = missing(listOf(temperatureTask(readings = 2)), mapOf(TASK_ID to listOf("4,5")))

        assertEquals(setOf(TemperatureSlot(TASK_ID, 1)), owed)
    }

    @Test
    fun `a field beyond the readings the task declares is not asked for`() {
        // The task owes what it declares: an extra slot the form happens to hold is not a reading the server
        // asks for, and the rule counts the declared range rather than the list's length.
        val owed = missing(listOf(temperatureTask(readings = 1)), mapOf(TASK_ID to listOf("4,5", "-18")))

        assertEquals(emptySet<TemperatureSlot>(), owed)
    }

    @Test
    fun `a task that is not a temperature task is never asked for readings`() {
        assertEquals(emptySet<TemperatureSlot>(), missing(listOf(checkTask()), emptyMap()))
    }

    // ---- What the body carries once the form may send ----

    @Test
    fun `the body carries one entry per complete reading, located at the task's name and one-based`() {
        val readings = reportTemperatures(
            listOf(temperatureTask(readings = 2)),
            mapOf(TASK_ID to listOf("4,5", "-18")),
            String::toReading,
        )

        assertEquals(2, readings.size)
        // The location is the task's own name, not a free-text field this client invents.
        assertEquals(NAME, readings[0].location)
        // A Brazilian operator types 4,5 and toDoubleOrNull on that string returns null, so the reading
        // would be dropped and the server would blame a missing temperature.
        assertEquals(BigDecimal("4.5"), readings[0].value)
        // One-based, because the server accepts 1 to 3.
        assertEquals(1, readings[0].readingIndex)
        assertEquals(NAME, readings[1].location)
        assertEquals(BigDecimal("-18"), readings[1].value)
        assertEquals(2, readings[1].readingIndex)
    }

    @Test
    fun `a blank reading is left out of the body rather than sent as a zero`() {
        // Sending zero for a blank field would store a temperature nobody measured. Leaving it out is what
        // lets the server say which reading is actually missing.
        val readings = reportTemperatures(
            listOf(temperatureTask(readings = 2)),
            mapOf(TASK_ID to listOf("4,5", "   ")),
            String::toReading,
        )

        assertEquals(1, readings.size)
        assertEquals(BigDecimal("4.5"), readings.single().value)
        assertEquals(1, readings.single().readingIndex)
    }

    private companion object {
        /** A temperature task's id and name, distinct from the other types' so a wrong pick shows. */
        const val TASK_ID = 5
        const val CATEGORY_ID = 2
        const val NAME = "Câmara Principal"
    }
}
