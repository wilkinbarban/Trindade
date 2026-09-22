package com.trindade.app.loading

import com.trindade.app.contract.models.ScheduleResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.contract.models.UpdateScheduleRequest
import com.trindade.app.di.NetworkModule
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * The loading edit screen's state: what it seeds from a row, what it offers, and what it sends back.
 *
 * Every claim below is about a rule that fails silently if it is inverted, so each test names the seam it
 * is riding on rather than the method it calls:
 *
 *  * **The read is the day's, not the row's.** There is no single-entry read, so the row is found inside
 *    the day it was opened from, and a row that day does not carry is a row this screen cannot edit.
 *  * **The window is the server's and is never computed here.** The row's two permission flags are the
 *    only answer to whether an edit is offered, and the failing direction is the one that matters: a row
 *    the server did not positively mark editable is read-only, so an edit this client offers is one the
 *    server accepts.
 *  * **The body is one field.** The server merges a schedule update field by field, so naming the date,
 *    the driver or the vehicle is what would clear one of them -- and the assertion is on the request the
 *    fake received, plus the bytes the app's own serializer would put on the wire for it.
 *  * **The picks are the day's slots with this row's own effect on each window.** `FleteroQuota` is the
 *    counter the grid draws and the question is its own "if this entry moved here, how many would there
 *    be", asked of every option including the one the row already sits in.
 *  * **A refusal is the server's sentence where the server wrote one.** The 403 is the one-hour window and
 *    is asserted character for character; the other arms keep the loading screen's own sentences, distinct
 *    from each other, and only a server that was never reached gets the app's.
 *  * **A read is cancelled and a write is finished.** The token is what keeps a superseded save off a
 *    screen it no longer belongs to, and the fake counts the writes that ran past their own suspension so
 *    that "dropped" cannot be confused with "torn down".
 *
 * `Dispatchers.setMain` is required because `viewModelScope` runs on Main, which a JVM test has no
 * implementation of until one is installed.
 */
class LoadingEditViewModelTest {

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

    private fun viewModel(api: FakeLoadingApi) = LoadingEditViewModel(LoadingRepository(api))

    /**
     * The app's own serializer, called on the provider rather than through Dagger.
     *
     * `NetworkModule` is an object and the provider is a plain function, so a test can have the real
     * configuration without standing up a component. The same call `ScheduleUpdatePayloadTest` makes, and
     * for its reason: the body this test asserts is the body that setting produces, not a second statement
     * of what it is believed to be.
     */
    private val appJson: Json = NetworkModule.provideJson()

    // ---- The day the fixtures load ----

    /**
     * One row of the day, with the two permission flags the server computes together passed straight
     * through.
     *
     * Both are required in the schedule contract rather than nullable as they are on a report, so there is
     * no absent case to send -- what the wire allows instead is a pair that disagrees with itself, and that
     * is one of the combinations the read-only test below loads.
     */
    private fun row(
        id: Int = ENTRY_ID,
        slot: String = "04:00",
        type: SchedulesResponseSchedulesInner.DriverType = SchedulesResponseSchedulesInner.DriverType.fletero,
        canEdit: Boolean = true,
        readOnly: Boolean = false,
    ) = FakeLoadingApi.entry(id = id, slot = slot, type = type).copy(canEdit = canEdit, readOnly = readOnly)

    /**
     * The day the screen opened on: the row, one fletero half an hour from it, and a company driver far
     * enough away to be no part of any window.
     *
     * The company driver is not decoration: it is what makes the count below a count of fleteros rather
     * than a count of rows, which is the distinction `FleteroQuota` exists for.
     */
    private fun day() = listOf(
        row(id = ENTRY_ID, slot = "04:00"),
        row(id = OTHER_FLETERO_ID, slot = "04:30"),
        row(id = CASA_ID, slot = "08:00", type = SchedulesResponseSchedulesInner.DriverType.casa),
    )

    private fun fake(
        schedules: List<SchedulesResponseSchedulesInner>? = day(),
        timeSlots: List<String>? = SLOTS,
        updateResponse: Response<ScheduleResponse>? = null,
        updateFailure: Throwable? = null,
        gateUpdates: Boolean = false,
    ) = FakeLoadingApi(
        schedulesToReturn = schedules,
        timeSlotsToReturn = timeSlots,
        updateResponse = updateResponse,
        updateFailure = updateFailure,
        gateUpdates = gateUpdates,
    )

    // ---- The tests ----

    /**
     * The read, and the two things it produces: the row and the slot the picker opens on.
     *
     * The date is asserted before the state is, because the row is only reachable through the day: a client
     * that read some other day would still find a row with this id in a fixture where every day answers the
     * same list, so the day the read carried is the half of this rule a state assertion cannot see.
     *
     * The seeded slot is the row's own, which is what makes the save dead until the operator moves it and
     * what a screen with no selection at all would show as an empty picker.
     */
    @Test
    fun `the row and its slot are seeded from the day it was opened from`() {
        val api = fake()
        val model = viewModel(api)

        model.load(ENTRY_ID, DATE)

        assertEquals(DATE, api.lastSchedulesDate)

        val state = model.state.value
        assertEquals(ENTRY_ID, state.entry?.id)
        assertEquals("04:00", state.timeSlot)
        assertEquals(false, state.loading)
        assertNull(state.message)
        // The positive control for the read-only rule below: this fixture's row is the server's editable
        // one, so the failing direction there is about the flags and not about a form that never opens.
        assertEquals(false, state.readOnly)
    }

    /**
     * The picker's options: the day's configured slots, in the server's order.
     *
     * Configured rather than occupied, because the question the picker answers is where else this row could
     * go, and an empty slot is exactly the answer. The order is the server's for the same reason the list
     * is: the settings define it and a client that sorted it would be deciding what an operator sees first.
     */
    @Test
    fun `the picker's options are the day's time slots`() {
        val model = viewModel(fake())

        model.load(ENTRY_ID, DATE)

        assertEquals(SLOTS, model.state.value.offers.map { it.timeSlot })
    }

    /**
     * Each option's quota, which is the window this row would leave in that slot rather than the one that is
     * there now.
     *
     * The figure is `FleteroQuota.countInWindow(..., excludeId = row.id)` plus the row itself when it is a
     * fletero, and every number in the assertion below fails under a different inversion of that:
     *
     *  * `1` at 06:00 and 09:00 is the row counted out of and back into a window that is otherwise empty --
     *    a count that never adds the row reads `0`, and one that never excludes it reads the same `1` for the
     *    wrong reason only if the row's own slot were in the window, which it is not at 06:00.
     *  * `2` at 04:00 is the row's current slot, where excluding it and adding it back is the same question
     *    asked of a slot it already occupies: the web's own expression -- it excludes the row only when the
     *    candidate slot differs -- counts the row and then adds it again and reads `3` here, which is why
     *    the figure is asserted exactly rather than as "at least one".
     *  * `2` at 04:30 is the move the operator is considering: the other fletero of that window, plus this
     *    row arriving in it.
     *  * the company driver at 08:00 contributes nothing anywhere, which is asserted by 09:00 reading `1`
     *    rather than `2`: 08:00 is an hour from it exactly, and the boundary is strict.
     */
    @Test
    fun `each option carries the window this row would leave in it`() {
        val model = viewModel(fake())

        model.load(ENTRY_ID, DATE)

        val offers = model.state.value.offers
        assertEquals(listOf(2, 2, 1, 1), offers.map { it.fleteros })
        assertEquals(listOf(false, false, false, false), offers.map { it.exceeded })
    }

    /**
     * The mark on an option that this row would push past the limit, and the boundary on both sides of it.
     *
     * Two options, because one cannot separate the two things this mark has to get right. At 04:00 three
     * other fleteros share the hour and this row arriving there is the fourth, so the option is marked --
     * which an option computed without the row's own contribution reads as three and calls clear. At 09:00
     * exactly two others do, and this row makes three: that is the limit itself, which the grid prints as
     * a denominator and not as an excess, and an option that marked at `>=` rather than at `>` would say
     * otherwise.
     *
     * Neither mark withholds the option: the server accepts a fourth fletero by an explicit business
     * decision, and the web draws that the same way.
     */
    @Test
    fun `an option is marked exceeded only where this row would push it past the limit`() {
        val crowded = listOf(
            row(id = ENTRY_ID, slot = "04:00"),
            row(id = 21, slot = "04:15"),
            row(id = 22, slot = "04:30"),
            row(id = 23, slot = "04:45"),
            row(id = 24, slot = "09:00"),
            row(id = 25, slot = "09:15"),
        )
        val model = viewModel(fake(schedules = crowded, timeSlots = listOf("04:00", "09:00")))

        model.load(ENTRY_ID, DATE)

        val offers = model.state.value.offers.associateBy { it.timeSlot }
        assertEquals(4, offers.getValue("04:00").fleteros)
        assertEquals(true, offers.getValue("04:00").exceeded)
        assertEquals(3, offers.getValue("09:00").fleteros)
        assertEquals(false, offers.getValue("09:00").exceeded)
    }

    /**
     * The body of an update: the chosen slot, and not one field of the row it came from.
     *
     * Two assertions, because the failure has two halves and neither one shows the other. The Kotlin object
     * is what holds a field the screen echoed: `UpdateScheduleRequest`'s fields all default to null, and the
     * server merges field by field -- `data.X !== undefined ? data.X : existing.X` -- so an explicit null for
     * the date, the driver or the vehicle is an instruction to clear it, which is exactly what an operator
     * who moved a time slot never asked for. And the bytes are what the object cannot state: the app's own
     * serializer keeps fields left at their default off the wire, so a body that named the row's real date,
     * driver and vehicle would arrive as those keys. `ScheduleUpdatePayloadTest` is where the serializer's
     * half is pinned; this is the half the screen owns.
     *
     * The exclusion of `driver_type` from the assertion is not an omission: the request can carry it, the
     * server resolves the driver's own type regardless, and the point is that this screen names nothing.
     */
    @Test
    fun `an update carries the chosen slot and nothing else`() {
        val api = fake()
        val model = viewModel(api)

        model.load(ENTRY_ID, DATE)
        model.onTimeSlotChange(DESTINATION)
        model.submit()

        assertEquals(ENTRY_ID, api.updatedId)

        val body = api.updatedBody!!
        assertEquals(DESTINATION, body.timeSlot)
        assertNull(body.scheduleDate)
        assertNull(body.driverType)
        assertNull(body.driverId)
        assertNull(body.vehicleId)

        // The whole body, character for character, rather than key by key: what must not appear is exactly
        // the four fields above, and a key-by-key assertion can only pin the keys somebody remembered.
        assertEquals(
            """{"time_slot":"$DESTINATION"}""",
            appJson.encodeToString(UpdateScheduleRequest.serializer(), body),
        )

        assertEquals(true, model.state.value.saved)
    }

    /**
     * The save is dead until the slot moves, and dead again when it moves back.
     *
     * The middle assertion is what makes the two dead ones mean something: without it this test would pass
     * against a form whose save is never offered. The rule is the web's own `disabled={saving ||
     * editSlot === entry.time_slot}`, and it is about the write rather than the screen -- a row saved into
     * the slot it already sits in is a request whose only possible effect is a duplicate refusal.
     *
     * The last pair is the same rule read from the other side: with the save dead, `submit` sends nothing,
     * which is asserted on the request the fake did not receive.
     */
    @Test
    fun `the save is dead until the slot changes, and dead again if it changes back`() {
        val api = fake()
        val model = viewModel(api)

        model.load(ENTRY_ID, DATE)
        assertEquals(false, model.state.value.canSubmit)

        model.onTimeSlotChange(DESTINATION)
        assertEquals(true, model.state.value.canSubmit)

        model.onTimeSlotChange("04:00")
        assertEquals(false, model.state.value.canSubmit)

        model.submit()
        assertNull(api.updatedBody)
    }

    /**
     * The read-only rule, in the three directions that withhold an edit and in the one that offers it.
     *
     * One combination cannot separate the rule from a form that is read-only whatever it is handed, so all
     * four are here. The server computes the two flags together (`readOnly` there is exactly `!canEdit`), so
     * the first is the ordinary closed window and the two after it are the shapes in which the wire lets the
     * pair disagree with itself: nothing was positively marked editable in the second, and the third says
     * read-only where the fourth says editable.
     *
     * Each leg asserts the same three things, and the third is the one a state assertion cannot reach: with
     * no save offered, a submit sends no request at all, so an edit the server refused is never even asked
     * for.
     */
    @Test
    fun `a row the server did not mark editable offers no edit and submits nothing`() {
        val closed = listOf(
            "the window has closed" to row(canEdit = false, readOnly = true),
            "nothing was marked editable" to row(canEdit = false, readOnly = false),
            "read-only was stated" to row(canEdit = true, readOnly = true),
        )

        closed.forEach { (what, entry) ->
            val api = fake(schedules = listOf(entry))
            val model = viewModel(api)

            model.load(ENTRY_ID, DATE)
            model.onTimeSlotChange(DESTINATION)
            model.submit()

            assertTrue("$what: the form is drawn read-only", model.state.value.readOnly)
            assertFalse("$what: a save was offered", model.state.value.canSubmit)
            assertNull("$what: an update was sent", api.updatedBody)
        }

        // The fourth combination, and the reason the three above are about the flags rather than about a
        // screen that never saves: the editable row does send its request.
        val open = fake()
        val editable = viewModel(open)
        editable.load(ENTRY_ID, DATE)

        assertFalse(editable.state.value.readOnly)

        editable.onTimeSlotChange(DESTINATION)
        editable.submit()

        assertEquals(DESTINATION, open.updatedBody?.timeSlot)
    }

    /**
     * The two reads that leave no row to edit, and the two sentences that keep them apart.
     *
     * A day that answered without this row is a row that is gone -- deleted between the grid and this
     * screen -- and a day that was never answered is a server this client could not ask. The distinction is
     * the one `LoadingRepository`'s read already carries as null against a list, and collapsing it would
     * tell the operator their entry was removed when the truth was a network.
     *
     * The failing direction is the same one the reports surface states for its own missing report: no row
     * means read-only, so a load that failed cannot leave a form that offers a save.
     */
    @Test
    fun `a day without the row and a day that was never read are not the same refusal`() {
        val missing = viewModel(fake(schedules = listOf(row(id = OTHER_FLETERO_ID))))
        missing.load(ENTRY_ID, DATE)

        assertNull(missing.state.value.entry)
        assertEquals(true, missing.state.value.readOnly)
        assertEquals(false, missing.state.value.canSubmit)
        // A fragment rather than the sentence, which is the way this file reads copy the app writes itself:
        // what is under test is which refusal was recognised, not the wording of it.
        assertTrue(missing.state.value.message.orEmpty().contains("não encontrado", ignoreCase = true))

        val unread = viewModel(fake(schedules = null))
        unread.load(ENTRY_ID, DATE)

        assertNull(unread.state.value.entry)
        assertEquals(false, unread.state.value.canSubmit)
        assertEquals(LoadingViewModel.UNREACHABLE, unread.state.value.message)
        assertNotEquals(missing.state.value.message, unread.state.value.message)

        // The third shape, which is neither of the two above: the row arrived and the slots it could be
        // moved to did not. There is no edit to offer -- a picker with no options has no destination in
        // it -- and the sentence is the connection's, because a slot list the server never answered is not
        // a row that is gone.
        val noSlots = viewModel(fake(timeSlots = null))
        noSlots.load(ENTRY_ID, DATE)

        assertEquals(ENTRY_ID, noSlots.state.value.entry?.id)
        assertEquals(emptyList<LoadingEditViewModel.SlotOffer>(), noSlots.state.value.offers)
        assertEquals(false, noSlots.state.value.canSubmit)
        assertEquals(LoadingViewModel.UNREACHABLE, noSlots.state.value.message)
    }

    /**
     * The window's refusal, which is the server's sentence and is shown as it stands.
     *
     * `loading.service.ts` refuses a PATCH outside the first hour with exactly these words, and the
     * repository's write result carries the status code rather than the body -- so the sentence is asserted
     * character for character, as the second place it is written, and the two copies are held equal by this
     * assertion. The generic sentence is asserted to be absent beside it: "try again" is the one thing this
     * refusal cannot be answered with, because it is the one refusal on this route that no retry can change.
     *
     * The row is the fixture's editable one, so the refusal really is the server's answer and not this
     * form's own read-only guard withholding the submit.
     */
    @Test
    fun `the window refusal is shown in the server's own words`() {
        val api = fake(updateResponse = Response.error(403, FakeLoadingApi.EMPTY_BODY))
        val model = viewModel(api)

        model.load(ENTRY_ID, DATE)
        model.onTimeSlotChange(DESTINATION)
        model.submit()

        assertEquals(SERVER_WINDOW_REFUSAL, model.state.value.message)
        assertNotEquals(LoadingViewModel.GENERIC, model.state.value.message)
        assertEquals(false, model.state.value.saved)
    }

    /**
     * A server that was never reached, which is the only failure this screen puts its own words to.
     *
     * The repository answers `Unreachable` for a call that threw, and the sentence is the loading screen's
     * own -- one copy of it in the app, shared rather than written again -- asserted against that constant
     * and against the server's refusal so the two cannot be confused for each other. The write is counted
     * beside it because an unreachable server refused nothing: the request left, and it is the answer that
     * never came.
     */
    @Test
    fun `a write that never reached the server says so in the app's own words`() {
        val api = fake(updateFailure = IOException("no route to host"))
        val model = viewModel(api)

        model.load(ENTRY_ID, DATE)
        model.onTimeSlotChange(DESTINATION)
        model.submit()

        assertEquals(LoadingViewModel.UNREACHABLE, model.state.value.message)
        assertNotEquals(SERVER_WINDOW_REFUSAL, model.state.value.message)
        assertEquals(false, model.state.value.saved)
        assertEquals(1, api.updatesThatRanToCompletion)
    }

    /**
     * The refusals the repository tells apart stay apart, and the one it cannot explain gets the generic.
     *
     * The repository's write result carries a status code for every refusal, which is the only reason these
     * can be told apart at all: 409 is something already on that date, 404 is an entry that is gone, 400 is
     * data that is no longer valid, and 500 is a server failure with no business meaning this client can
     * read. Pairwise distinctness is the assertion, because a screen that collapsed any two of them would
     * hand the operator one fact where the server gave another -- and the last leg is the other half of the
     * same rule, since collapsing them all into the generic sentence would satisfy nothing here.
     *
     * The 403 is left out on purpose rather than by omission: its sentence is the server's and is asserted
     * exactly in the test above, and this test is about the arms the app writes for itself.
     */
    @Test
    fun `the refusals the repository tells apart stay distinct`() {
        val answers = listOf(400, 404, 409, 500).associateWith { code ->
            val model = viewModel(fake(updateResponse = Response.error(code, FakeLoadingApi.EMPTY_BODY)))
            model.load(ENTRY_ID, DATE)
            model.onTimeSlotChange(DESTINATION)
            model.submit()
            model.state.value.message
        }

        answers.forEach { (code, message) ->
            assertNotNull("$code answered nothing", message)
            assertNotEquals("$code answered with the window's refusal", SERVER_WINDOW_REFUSAL, message)
        }

        assertEquals(answers.values.size, answers.values.toSet().size)
        // The app's own sentence for a code the operator cannot act on. Asserted against the shared
        // constant rather than as a fragment, because this is the arm that must stay the fallback.
        assertEquals(LoadingViewModel.GENERIC, answers[500])
    }

    /**
     * The reset the route relies on, on both of the flags a previous arrival can leave standing, and on the
     * write that outlives the screen that started it.
     *
     * The first half is the flag on its own: a save lands, `saved` is true, and a load puts it back to
     * false. Without the reset the next arrival's `state.first { it.saved }` returns immediately from the
     * value the previous visit left, and the screen closes before the row it just asked for is drawn.
     *
     * The second half is the write still in the air, and it is the half a `saved`-only reset would still
     * fail. The gate holds the update, so `submitting` is true with `saved` still false -- a form the route
     * would draw with a spinner it never set going and a save it withholds, because `canSubmit` reads the
     * same flag. The load is called while that write is unfinished, and every assertion after it is about
     * what the new arrival is handed. Releasing the gate afterwards is what makes the last two say
     * something: the stale write gets its answer, and the only reason it cannot announce itself with it --
     * no message, no `saved`, no spinner -- is that the load dropped it.
     *
     * The final assertion is the other half of that promise and the half no state can carry. What the flags
     * prove is that a superseded write says nothing on this screen; what the operator's edit needs is that
     * the request still reached the server. A coroutine cancelled by the load produces a state identical to
     * the one above, so the fake counts the writes that resumed from their own suspension and ran to their
     * end, and the count beside the flags is the pair the token exists to hold apart.
     */
    @Test
    fun `a load forgets the save the previous arrival started`() {
        // A save that landed: the flag it set, and the load that has to clear it.
        val landedApi = fake()
        val landed = viewModel(landedApi)
        landed.load(ENTRY_ID, DATE)
        landed.onTimeSlotChange(DESTINATION)
        landed.submit()

        assertEquals(true, landed.state.value.saved)
        // The positive direction of the counter the last assertion reads, so that a zero there means "this
        // write was dropped" rather than "this seam never counted anything".
        assertEquals(1, landedApi.updatesThatRanToCompletion)

        landed.load(ENTRY_ID, DATE)
        assertEquals(false, landed.state.value.saved)
        assertEquals(false, landed.state.value.submitting)

        // A save still in the air when the next arrival loads.
        val api = fake(gateUpdates = true)
        val inFlight = viewModel(api)
        inFlight.load(ENTRY_ID, DATE)
        inFlight.onTimeSlotChange(DESTINATION)
        inFlight.submit()

        // The state the previous screen would have left drawn: a write in the air, and no answer yet.
        assertEquals(true, inFlight.state.value.submitting)
        assertEquals(false, inFlight.state.value.saved)

        inFlight.load(ENTRY_ID, DATE)
        assertEquals(false, inFlight.state.value.submitting)
        assertEquals(false, inFlight.state.value.saved)

        // The answer the stale write was waiting for, arriving after the load that replaced it.
        api.updateGates.single().complete(Unit)
        assertEquals(false, inFlight.state.value.saved)
        assertNull(inFlight.state.value.message)

        // The superseded save wrote nothing to this screen and still ran to its end. Cancelling the write
        // instead of bumping the token -- which is what the read beside it does -- passes every assertion
        // above and fails this one.
        assertEquals(1, api.updatesThatRanToCompletion)
    }

    /**
     * The other half of the arrival rule, on the read: an answer to the day this screen is no longer showing
     * is dropped rather than drawn.
     *
     * The older read is the failing one -- the day it asked for answers a 500 when it lands, which the fake
     * builds after its gate -- and it is released last, so what the assertion is about is an answer that
     * arrives after the arrival that replaced it. Without the guard the failure would be written over the
     * row that loaded fine, and the screen would say the server could not be reached about a day it had just
     * read.
     *
     * How the older read is stopped is not asserted here: this class cancels the read it started, the way
     * the dashboard cancels its own, and either that or a token leaves the same state. The claim is the one
     * the operator sees -- nothing the replaced arrival produced reaches this screen.
     */
    @Test
    fun `a read the next arrival replaced writes nothing over it`() {
        val api = FakeLoadingApi(
            schedulesToReturn = day(),
            timeSlotsToReturn = SLOTS,
            failForDate = DATE,
            gateSchedules = true,
        )
        val model = viewModel(api)

        model.load(ENTRY_ID, DATE)
        model.load(ENTRY_ID, OTHER_DATE)
        assertEquals(2, api.scheduleGates.size)

        // The arrival that stays, released first so the state is known before the older one lands.
        api.scheduleGates[1].complete(Unit)
        assertEquals(ENTRY_ID, model.state.value.entry?.id)
        assertNull(model.state.value.message)
        assertEquals(false, model.state.value.loading)

        // And the replaced one, which answers a failure for the day it was asked about.
        api.scheduleGates[0].complete(Unit)
        assertEquals(ENTRY_ID, model.state.value.entry?.id)
        assertNull(model.state.value.message)
        assertEquals(false, model.state.value.loading)
    }

    private companion object {
        /** The row the screen opened, and the two other rows of its day. */
        const val ENTRY_ID = 12
        const val OTHER_FLETERO_ID = 13
        const val CASA_ID = 14

        /** The day, and the day a later arrival asks for. Both distinct from the fixture's own. */
        const val DATE = "2026-09-18"
        const val OTHER_DATE = "2026-09-19"

        /** The slot the operator moves the row to, distinct from the row's own. */
        const val DESTINATION = "06:00"

        /**
         * The day's configured slots.
         *
         * Deliberately not in clock order: 06:00 sits between 04:30 and 09:00 because the settings define
         * the order the picker shows, and a client that sorted them would be deciding what the operator
         * reads first.
         */
        val SLOTS = listOf("04:00", "04:30", "06:00", "09:00")

        /**
         * The window's refusal, spelled out here because the constant that holds it is private.
         *
         * `LoadingEditViewModel.OUTSIDE_EDIT_WINDOW` lives in a private companion, so this copy is the only
         * thing a test in this package can compare against -- and it is the backend's sentence, taken from
         * `loading.service.ts`, which is what makes the assertion worth making: the app must not paraphrase
         * a refusal the server wrote.
         */
        const val SERVER_WINDOW_REFUSAL =
            "Registro somente leitura. Apenas o criador pode editar durante a primeira hora."
    }
}
