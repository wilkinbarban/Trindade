package com.trindade.app.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.contract.models.UpdateScheduleRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Editing one loading entry: the web's `LoadingEditPage`, for the one field it edits.
 *
 * A loading is a schedule of driver/vehicle/time-slot rows and not a report: there are no loading
 * items, no categories, no photos and no temperatures, so nothing of the report edit surface transfers
 * here and this class shares no state shape with it. What the web's page does with one row is one
 * thing -- it replaces that row with a `<select>` of the day's time slots, each annotated with the
 * quota it has left, and sends the chosen slot -- and that is the whole of what this class holds: the
 * row, the day's slots with their quotas, and the slot the operator picked.
 *
 * **The edit window is the server's, and this class never computes it.** The row arrives with
 * `canEdit` and `readOnly` on it, and [isReadOnly] renders the two as they came; the boundary -- which
 * creator, and the first hour after the row was made -- lives in the backend
 * (`loading.service.ts`, `projectHistoryPermissions(actor, existing, 'one-hour')`), and the SPA derives
 * it nowhere either. A client that recomputed it would be a second implementation of a business rule,
 * and a phone's clock and timezone are exactly what a second implementation gets wrong.
 *
 * **The body carries the chosen slot and nothing else.** The server merges a schedule update field by
 * field -- `data.X !== undefined ? data.X : existing.X` in `loading.service.ts` -- so a field left out
 * is a field kept, while an explicit `null` is a value and is written. The loaded row is therefore
 * never echoed back into the request: naming the date, the driver or the vehicle would be three
 * chances to clear one of them. The serializer half of that rule is proved in
 * `ScheduleUpdatePayloadTest`, on the app's own `Json`.
 */
@HiltViewModel
class LoadingEditViewModel @Inject constructor(
    private val repository: LoadingRepository,
) : ViewModel() {

    /** One option of the picker: a slot of the day, and the window this row would leave in it. */
    data class SlotOffer(
        val timeSlot: String,
        /**
         * How many fleteros the hour around [timeSlot] would hold with this row moved into it.
         *
         * The server's own `time_slot` strings decide that and never the device clock: `FleteroQuota` is
         * the counter the grid already draws, and this is that object's own edit-screen question -- "if
         * this entry moved here, how many would there be" -- rather than a second rule. The row is left
         * out of the count first and added back only when it is a fletero, because a company driver
         * brings nothing to a fletero count; the row is left out for the slot it already sits in as much
         * as for the others, so the same question is answered for every option.
         *
         * The denominator the operator reads beside this figure is [FleteroQuota.LIMIT], which is what
         * the grid's own counter uses.
         */
        val fleteros: Int,
    ) {
        /**
         * Whether that window would be past [FleteroQuota.LIMIT].
         *
         * The same strict boundary `FleteroQuota.isExceeded` states, and still only an indication: the
         * server accepts a fourth fletero by an explicit business decision, so this marks the option
         * and withholds nothing.
         */
        val exceeded: Boolean get() = fleteros > FleteroQuota.LIMIT
    }

    data class UiState(
        val loading: Boolean = true,
        /** The row being edited, or null when the day it was opened from does not carry it. */
        val entry: SchedulesResponseSchedulesInner? = null,
        /**
         * The picker's options: the day's slots, in the order the server configured them.
         *
         * The configured slots and not the slots the day's rows happen to sit in, which is what the web
         * offers: the picker answers "where else could this row go", and a slot nothing occupies is
         * exactly the place an operator is moving a row to.
         */
        val offers: List<SlotOffer> = emptyList(),
        /** The slot the operator picked, seeded from the row's own. */
        val timeSlot: String? = null,
        val submitting: Boolean = false,
        /** Set once the server accepted the update, which is what the screen leaves on. */
        val saved: Boolean = false,
        val message: String? = null,
    ) {
        /**
         * Whether the form is drawn read-only, read off the row rather than decided here.
         *
         * The answer is [isReadOnly], the predicate beside this class, and not a copy of it: the surface
         * that offers the way in asks the same question, so two copies would be two answers to one
         * question -- a row the grid draws an edit action for while this screen draws no save at all,
         * which is the contradiction one of them would eventually be edited into. A missing row is
         * read-only for the same reason: there is nothing there to edit, and the load that failed says so.
         */
        val readOnly: Boolean get() = entry?.isReadOnly() ?: true

        /** The row, the day's slots to move it to, and a picked slot: the form the screen can draw. */
        val ready: Boolean get() = !loading && entry != null && timeSlot != null && offers.isNotEmpty()

        /**
         * Submitting is offered only for a row the server called editable, only once, and only once the
         * slot moved.
         *
         * The last clause is the web's own gate -- `disabled={saving || editSlot === entry.time_slot}` --
         * and it is about the write rather than about the screen: a row saved into the slot it is already
         * in is a request whose only possible effect is a 409 or a no-op, and the server has nothing to
         * be told.
         */
        val canSubmit: Boolean
            get() = ready && !readOnly && !submitting && !saved && timeSlot != entry?.timeSlot
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The read in flight, if there is one. Held so the next arrival can cancel it, which is the
     * dashboard's own shape and its own reason: this view model is scoped to the activity's store and
     * outlives the composition that draws it, so two reads can really be in the air at once, and an
     * answer nobody is waiting for is waste -- no screen is left to draw it, and no fact about the entry
     * is lost by dropping it.
     */
    private var reading: Job? = null

    /**
     * Identifies the newest load, so a save an older one started cannot announce itself on this screen.
     *
     * The history and loading view models' own request token, for their reason: two arrivals really can
     * be in the air at once, because this instance outlives the composition. Without the token, a write
     * one arrival started lands on the next one's screen -- a spinner it never set going, a form whose
     * save is withheld, and then `saved`, which is the very flag the next arrival leaves on.
     *
     * The write itself is not cancelled for it, and that is the whole difference between this and the
     * read above. Cancelling the coroutine would tear down the call it is awaiting, so an edit the
     * operator submitted and then left the screen for could be abandoned before the server ever saw it;
     * a write the operator asked for is work that should land. What the token drops is only that write's
     * claim on a screen it no longer belongs to: the request runs on and the server applies it whether
     * or not this screen is still there, and its answer writes nothing when it arrives.
     */
    private var newestLoad = 0

    /**
     * The row, the day's slots and the row's own slot, read together.
     *
     * There is no single-entry read: the day is what `GET /api/loading/schedules?date=` takes, so the
     * row is found inside the day it was opened from, and a row that day does not carry is a row this
     * screen cannot edit -- the same missing row the reports surface answers for.
     *
     * The picker's options come from the second read and the row's count comes from the first, because a
     * slot's quota is what the day's other rows make of it; loading them together is what keeps the
     * figure the operator reads equal to the state the row is in.
     */
    fun load(scheduleId: Int, date: String) {
        // The read in flight is cancelled first: see [reading]. The token below is a different guarantee
        // for a different thing -- the write -- and both are taken before anything is read.
        reading?.cancel()
        newestLoad++

        // A write a previous arrival left behind is one this arrival cannot finish and did not start, so
        // both flags it set go back to false with the load. `saved` is the obvious one: it means "the
        // write this screen sent was accepted", and left standing it would be read by the next arrival's
        // `state.first { it.saved }` and close the screen before the row it just asked for was drawn.
        // `submitting` is the same failure one step earlier: left standing it would draw a spinner this
        // arrival never set going, and when that write landed it would set `saved` -- the stale close
        // again, a moment later. The token is what stops that now.
        _state.update { it.copy(loading = true, message = null, saved = false, submitting = false) }

        reading = viewModelScope.launch {
            val schedules = repository.schedules(date)
            val slots = repository.timeSlots()
            val row = schedules?.firstOrNull { it.id == scheduleId }

            _state.update { current ->
                current.copy(
                    loading = false,
                    entry = row,
                    offers = if (row == null) emptyList() else slotOffers(schedules.orEmpty(), row, slots.orEmpty()),
                    timeSlot = row?.timeSlot,
                    // Only a read that was refused is worth a sentence of its own, and the two refusals
                    // are not the same one: a day the server answered without this row is a row that is
                    // gone, while a day that was never answered leaves nothing to say about the row at
                    // all. A missing slot list is the second case rather than a third: the form cannot
                    // offer a destination, which is exactly what no connection to the server means here.
                    message = when {
                        schedules == null -> UNREACHABLE
                        row == null -> NOT_FOUND
                        slots == null -> UNREACHABLE
                        else -> null
                    },
                )
            }
        }
    }

    /** The slot the operator picked. Nothing is sent until [submit]. */
    fun onTimeSlotChange(timeSlot: String) = _state.update { it.copy(timeSlot = timeSlot, message = null) }

    /**
     * Sends the update: the chosen slot, and nothing else.
     *
     * The body is built from the form and never from the row that was loaded -- see this class's comment
     * for why naming another field would be a way to clear it -- and the id is the loaded row's, which is
     * the one thing about the target the form does not carry.
     *
     * **The write is not cancelled when the operator leaves the screen.** A load started afterwards takes
     * a new [newestLoad] token, and that is the whole of what happens to a save already in the air: the
     * request runs to its end and the server applies it, because the edit is work the operator asked for
     * and cancelling the coroutine would tear down the call it is awaiting before the server ever saw it.
     */
    fun submit() {
        val current = state.value
        if (!current.canSubmit) return
        val row = current.entry ?: return
        val slot = current.timeSlot ?: return

        // The arrival this save belongs to, read before the request is launched: a load that starts while
        // the write is in the air takes a newer token, and this save then belongs to a screen that is gone.
        val load = newestLoad

        _state.update { it.copy(submitting = true, message = null) }

        // `submitting` is set here as the write starts and cleared on every path that runs when it
        // finishes, and the token is what keeps a later arrival from being handed any of it: a save whose
        // token was superseded writes nothing at all -- not the flag, not `saved`, not the message.
        viewModelScope.launch {
            val result = repository.updateSchedule(row.id, UpdateScheduleRequest(timeSlot = slot))

            if (load != newestLoad) return@launch

            _state.update { previous ->
                when (result) {
                    // Saved only means the server took the body: the screen leaves on it, and the row the
                    // server echoed back is not written into the form -- the load that follows an arrival
                    // is the confirmation, the same shape the other screens in this app use to read back a
                    // write.
                    is ScheduleWriteResult.Saved -> previous.copy(submitting = false, saved = true)
                    is ScheduleWriteResult.Refused ->
                        previous.copy(submitting = false, message = refusalMessage(result.statusCode))
                    ScheduleWriteResult.Unreachable -> previous.copy(submitting = false, message = UNREACHABLE)
                }
            }
        }
    }

    /**
     * One offer per configured slot, each carrying the window this row would leave in it.
     *
     * The count is [FleteroQuota]'s own "if this entry moved here" question, asked of every option: the row
     * is excluded from the count and then added back when it is a fletero, because that is what moving it
     * there would do. Asking it for the slot the row already sits in as well is what keeps one question
     * from having two answers on one screen: the option the operator opened the picker on has to read the
     * same way as the ones they are considering.
     *
     * The web's own expression is not that one, and the difference is recorded here rather than copied:
     * `LoadingEditPage` passes `slot === entry.time_slot ? undefined : entry.id` as the exclusion, so for
     * the row's current slot it counts the row and then adds it again, reading a three where the grid
     * beside it reads a two. That is a figure about a row that is not going to move, which changes nothing
     * the operator can act on, and reproducing it would mean asking [FleteroQuota] the question it does not
     * document while the picker's other options are asked the one it does.
     */
    private fun slotOffers(
        schedules: List<SchedulesResponseSchedulesInner>,
        row: SchedulesResponseSchedulesInner,
        slots: List<String>,
    ): List<SlotOffer> = slots.map { slot ->
        SlotOffer(
            timeSlot = slot,
            fleteros = FleteroQuota.countInWindow(schedules, slot, excludeId = row.id) +
                if (row.driverType == SchedulesResponseSchedulesInner.DriverType.fletero) 1 else 0,
        )
    }

    /**
     * What to say about a refusal, by the status code the server answered with.
     *
     * The 403 is the one arm on this route whose meaning is not a business detail the operator can fix: it
     * is the one-hour window, which is the backend's own rule (`C1`), and the sentence is the server's own
     * -- `loading.service.ts` writes it character for character -- so it is shown as it stands. A
     * UI-authored rewrite of it would be a second statement of a rule this client deliberately does not
     * implement, and the operator receives a different reason from the one the server gave.
     *
     * The rest are the loading screen's own sentences for the arms it already tells apart, code for code,
     * because the two routes refuse the same facts about the same day: 409 is a driver or a vehicle already
     * on that date, 404 is an entry that is gone, and 400 is data that is no longer valid -- a driver or a
     * vehicle that has been deactivated, which the body this screen sends cannot change. Every other code
     * is the generic sentence rather than a guess at a cause this client cannot see.
     */
    private fun refusalMessage(statusCode: Int): String = when (statusCode) {
        403 -> OUTSIDE_EDIT_WINDOW
        409 -> DUPLICATE
        404 -> GONE
        400 -> INVALID
        else -> LoadingViewModel.GENERIC
    }

    private companion object {
        const val NOT_FOUND = "Lançamento não encontrado."

        /**
         * The window's refusal, which is the server's sentence rather than this app's.
         *
         * `loading.service.ts` answers a PATCH outside the first hour with exactly these words, and the
         * repository's write result carries the status code and not the body, so this constant is the only
         * place they can be shown from. Written here character for character and never paraphrased: the
         * refusal is the server's own rule, and it is also the only refusal on this route that no retry
         * can change, so its wording is what tells the operator why the edit is closed.
         */
        const val OUTSIDE_EDIT_WINDOW =
            "Registro somente leitura. Apenas o criador pode editar durante a primeira hora."

        /** The loading screen's own 409, character for character: something is already on that date. */
        const val DUPLICATE = "Esse motorista ou veículo já está nessa data."

        /** The loading screen's own 404 for a single entry, character for character. */
        const val GONE = "O lançamento não existe mais."

        /**
         * The loading screen's own 400, character for character.
         *
         * The two routes refuse the same kind of body, and on this one the field the server refuses is
         * always one the screen cannot send: the driver or the vehicle the row already carries is inactive
         * or gone, which is a row the operator has to fix elsewhere. The sentence says what the operator
         * can do -- look at the data -- rather than naming a cause this client was not told.
         */
        const val INVALID = "Verifique os dados e tente de novo."

        /** The loading screen's own answer for a server that was not reached. */
        const val UNREACHABLE = LoadingViewModel.UNREACHABLE
    }
}

/**
 * Whether the server says this loading row may not be edited.
 *
 * One predicate for the screens that ask: the grid decides whether to offer the way in, and this edit
 * surface decides whether to draw a save, and two copies of this expression would be two answers to one
 * question. It fails closed -- a row the server did not positively mark editable, in either flag, is drawn
 * read-only -- so an edit this client offers is one the server accepts, and a client that contradicted the
 * server would be answering a 403 the operator did nothing to earn.
 *
 * The reports surface's predicate is `readOnly ?: (canEdit != true)` and this is not that expression,
 * because the two rows do not carry the same flags: a report's `canEdit` and `readOnly` are nullable and
 * may be absent, which is what the elvis is for, while a schedule's are required in the contract -- the
 * server computes both together, from one `projectHistoryPermissions` call, and `loading.schema.ts` will
 * not answer a row without them. So the shape that has to be stated here is what the two flags mean
 * together: the row is editable only where `readOnly` did not say otherwise *and* `canEdit` said yes, and
 * a pair that contradicts itself is read as the refusal. The web's own gate on this page is the second
 * half of it, `entry.canEdit &&`, and on the server the two agree by construction (`readOnly` there is
 * exactly `!canEdit`), so this reads the same answer the SPA does for every row the backend can send.
 */
internal fun SchedulesResponseSchedulesInner.isReadOnly(): Boolean = readOnly || canEdit != true
