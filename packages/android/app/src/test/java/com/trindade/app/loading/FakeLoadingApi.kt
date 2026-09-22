package com.trindade.app.loading

import com.trindade.app.contract.models.CreateDriverRequest
import com.trindade.app.contract.models.CreateScheduleRequest
import com.trindade.app.contract.models.DriverResponse
import com.trindade.app.contract.models.DriversResponse
import com.trindade.app.contract.models.DriversResponseDriversInner
import com.trindade.app.contract.models.ScheduleHistoryResponse
import com.trindade.app.contract.models.ScheduleHistoryResponseItemsInner
import com.trindade.app.contract.models.ScheduleHistoryResponsePagination
import com.trindade.app.contract.models.ScheduleResponse
import com.trindade.app.contract.models.SchedulesResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.TextResponse
import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateScheduleRequest
import com.trindade.app.contract.models.VehiclesResponse
import com.trindade.app.contract.models.VehiclesResponseVehiclesInner
import com.trindade.app.network.LoadingApi
import java.time.DayOfWeek
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * A `LoadingApi` that answers from memory, like the reports one and for the same reason: the interface
 * is a plain Kotlin interface, so the repository under test is the real one and only the network is
 * replaced.
 *
 * The methods no slice has needed yet throw rather than returning a plausible default, so a test cannot
 * pass while calling something it never meant to.
 */
class FakeLoadingApi(
    private val schedulesToReturn: List<SchedulesResponseSchedulesInner>? = emptyList(),
    /**
     * When true, every schedule read waits on its own gate before answering, so a test can hold two of
     * them in the air at once and choose which one lands last.
     *
     * The gate is released *before* the answer is built, on purpose: the answer is the one that is true
     * when it lands, which is how a test reproduces what the code could not otherwise see -- a stale
     * response arriving after a newer one, carrying the day the operator is no longer asking for.
     */
    private val gateSchedules: Boolean = false,
    /**
     * The date whose schedule read answers a 500 instead of a page.
     *
     * Compared where the answer is built and not where the call was made, which with [gateSchedules]
     * lets a test make the failing answer the *stale* one: the newer read of a different day has
     * already succeeded, so what the failure proves is that a superseded answer is dropped rather than
     * drawn -- without the token it would put "sem conexão" over a day that loaded fine.
     */
    private val failForDate: String? = null,
    /** Null makes the history call fail, the same convention as [schedulesToReturn] and for the same reason. */
    private val historyToReturn: ScheduleHistoryResponse? = defaultHistory(),
    /**
     * When set, the history call throws it instead of answering at all.
     *
     * A refused read and an abandoned one are different answers, and [historyToReturn]'s null can only
     * produce the first, because a repository that captured a cancellation would still hand the caller
     * a null either way. This hook is what lets a test give the repository a cancellation and check that
     * it is rethrown instead, which is the state the two failures must not be confused in.
     */
    private val historyFailure: Throwable? = null,
    /**
     * When set, the history is this many batches at the server's default page size, and the two batch
     * deletions really change it: a deletion removes one batch and a deactivation does not.
     *
     * A fixed [historyToReturn] cannot answer a paging test -- asking for page 2 and being handed page 1
     * is exactly the mistake such a test exists to catch -- and it cannot model the case the step-back
     * exists for, where deleting the last batch on the last page leaves that page empty.
     */
    private val historyItemCount: Int? = null,
    /**
     * When true, every history call waits on its own gate before answering, so a test can hold two of
     * them in the air at once and choose which one lands last.
     *
     * The gate is released *before* the answer is built, on purpose: the state the fake reads is the
     * state at release time, which is how a test reproduces what the code could not otherwise see -- a
     * stale response arriving after a newer one, carrying rows the operator is no longer asking for.
     */
    private val gateHistory: Boolean = false,
    private val timeSlotsToReturn: List<String>? = listOf("04:00", "04:30", "05:00"),
    private val driversToReturn: List<DriversResponseDriversInner> = defaultDrivers(),
    private val vehiclesToReturn: List<VehiclesResponseVehiclesInner> = defaultVehicles(),
    private val createResponse: Response<ScheduleResponse>? = null,
    /**
     * What an update answers. Null is the server taking the body and echoing the row, which is what it does;
     * a `Response.error` is the refusal the screen has to explain in its own words.
     */
    private val updateResponse: Response<ScheduleResponse>? = null,
    /**
     * When set, the update throws it instead of answering at all.
     *
     * The same hook as [historyFailure] and for the same reason: a refused write and an unreachable server
     * are different answers -- the first is the server's own sentence and the second is the app's -- and a
     * fake that could not tell them apart could not tell whether the screen had either.
     */
    private val updateFailure: Throwable? = null,
    /**
     * When true, every update waits on its own gate before answering, so a test can hold a save in the air
     * and see what the state says while it is there.
     *
     * The convention is [gateSchedules]'s and [gateHistory]'s, and so is why a gate and not a delay: the
     * fake answers immediately, so an in-flight write is a state no test could otherwise reach, and the
     * claims about it -- that `submitting` is set while it is in the air, and that a load drops it rather
     * than letting it report afterwards -- are about what happens between the request leaving and the
     * answer arriving.
     */
    private val gateUpdates: Boolean = false,
    private val deleteSucceeds: Boolean = true,
    /** What a batch deactivation answers. 200 with a body in practice; the fake never reads it either. */
    private val deactivateBatchResponse: Response<SuccessResponse> =
        Response.success(SuccessResponse(success = SuccessResponse.Success.entries.first())),
    /** What a batch deletion answers, 204 with no body in practice, and the same convention for a refusal. */
    private val deleteBatchResponse: Response<Unit> = Response.success(Unit),
    /** Null is the server refusing to render the text, which is the only failure the export has. */
    private val exportToReturn: String? = EXPORT_TEXT,
) : LoadingApi {

    var lastSchedulesDate: String? = null
        private set

    /**
     * The filters the last history call carried, all four at once.
     *
     * Grouped rather than four loose fields because the assertion these exist for is about the whole
     * query: an absent filter has to arrive as null and an empty one has to arrive empty, and four
     * separate fields let a test pin three of them and miss the one that moved.
     */
    var lastHistoryQuery: LoadingHistoryQuery? = null
        private set

    /**
     * Every history call, in order, which is what a paging test has to assert on.
     *
     * [lastHistoryQuery] is the last one, and the sequence is the only thing that can show what a
     * step-back did: the pages the view model asked for after a deletion are the behaviour under test,
     * and the state it settled on could have been reached without asking for the dismissed page at all.
     */
    val historyQueries = mutableListOf<LoadingHistoryQuery>()

    /** The batch dates the two lifecycle calls were made with, in the order they were made. */
    val deactivatedBatchDates = mutableListOf<String>()
    val deletedBatchDates = mutableListOf<String>()

    /** One gate per history call held open by [gateHistory], in the order the calls were made. */
    val historyGates = mutableListOf<CompletableDeferred<Unit>>()

    /** One gate per schedule read held open by [gateSchedules], in the order the calls were made. */
    val scheduleGates = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * How many batches the live history holds, counting down as deletions succeed.
     *
     * Writable on purpose: a test that wants the cascade the step-back rule is recursive for has to
     * empty more than one page at once, and a deletion cannot do that. Setting this to 0 mid-test is how
     * another operator's change is simulated, and it is the same state the fake serves pages from, so
     * the arithmetic under test stays the one the server does.
     */
    var remainingHistoryItems = historyItemCount ?: 0

    var lastExportDate: String? = null
        private set

    /** What the last create carried, which is how the payload rules below are asserted. */
    var createdBody: CreateScheduleRequest? = null
        private set

    /**
     * The last update's target and body, which is what the partial-update rule is asserted on.
     *
     * The body is kept as it arrived rather than summarized, because the claim is about the request the
     * screen sent and not about the state that produced it: the server merges a schedule update field by
     * field, so which fields a caller named is the whole of what it said.
     */
    var updatedId: Int? = null
        private set
    var updatedBody: UpdateScheduleRequest? = null
        private set

    /** One gate per update held open by [gateUpdates], in the order the calls were made. */
    val updateGates = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * How many updates resumed from their own suspension and ran to their end.
     *
     * Whether a superseded write finished or was torn down is invisible from the view model's state -- both
     * leave the same two flags -- and the difference is the whole reason a request token was chosen over a
     * cancellation: a write the operator asked for is work that has to land. The counter is incremented on
     * the line after the gate, the first line that runs only when the suspension resumed normally; a call
     * cancelled while it was held never reaches it.
     */
    var updatesThatRanToCompletion = 0
        private set

    var deletedIds = mutableListOf<Int>()
        private set

    override suspend fun schedules(date: String): Response<SchedulesResponse> {
        lastSchedulesDate = date

        if (gateSchedules) {
            val gate = CompletableDeferred<Unit>()
            scheduleGates += gate
            gate.await()
        }

        // After the gate and not before it: the failure is what this read answers when it lands, which
        // is the only way a held-open read can be the stale one that fails while a newer one succeeds.
        if (date == failForDate) return Response.error(500, EMPTY_BODY)

        val schedules = schedulesToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(SchedulesResponse(schedules = schedules))
    }

    override suspend fun history(
        date: String?,
        month: String?,
        page: Int?,
        pageSize: Int?,
    ): Response<ScheduleHistoryResponse> {
        historyFailure?.let { throw it }

        val query = LoadingHistoryQuery(date = date, month = month, page = page, pageSize = pageSize)
        lastHistoryQuery = query
        historyQueries += query

        if (gateHistory) {
            val gate = CompletableDeferred<Unit>()
            historyGates += gate
            gate.await()
        }

        val history = if (historyItemCount != null) {
            liveHistory(page ?: 1)
        } else {
            historyToReturn ?: return Response.error(500, EMPTY_BODY)
        }
        return Response.success(history)
    }

    /**
     * One page of a history the deletions shrink, at the server's own page size.
     *
     * The arithmetic is the server's: pages of [PAGE_SIZE], `totalPages` rounded up, and a page past
     * the end answering an empty list with a well-formed pagination rather than an error. `total` counts
     * batches rather than entries, because that is what this endpoint groups by.
     */
    private fun liveHistory(page: Int): ScheduleHistoryResponse {
        val total = remainingHistoryItems.coerceAtLeast(0)
        val start = (page - 1) * PAGE_SIZE
        val onPage = (total - start).coerceIn(0, PAGE_SIZE)

        return ScheduleHistoryResponse(
            items = (0 until onPage).map { offset ->
                val day = batchDate(start + offset + 1)
                historyItem(batchDate = day, loadingDate = loadingDate(day))
            },
            pagination = ScheduleHistoryResponsePagination(
                page = page,
                pageSize = PAGE_SIZE,
                total = total,
                totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE,
            ),
        )
    }

    /**
     * The [index]-th most recent batch date, counting back from a fixed day.
     *
     * Fixed rather than today's date so a test's rows are the same on any machine, and consecutive so
     * that every row's `batch_date` is distinct -- which is the key the endpoint groups by and the key
     * the screen uses for its rows.
     */
    private fun batchDate(index: Int): String = LAST_BATCH_DATE.minusDays((index - 1).toLong()).toString()

    /**
     * The loading date the server computes from a batch date: the same day on a Friday, the day after
     * on every other one. `%w` 5 is Friday in the server's `strftime`, and this is the same rule.
     *
     * Passed straight into [historyItem] by [liveHistory] so the two dates differ on the rows a test
     * pages through, which is what makes the distinction the client must not lose visible in a test.
     */
    private fun loadingDate(batchDate: String): String {
        val date = LocalDate.parse(batchDate)
        return if (date.dayOfWeek == DayOfWeek.FRIDAY) batchDate else date.plusDays(1).toString()
    }

    override suspend fun timeSlots(): Response<TimeSlotsResponse> {
        val slots = timeSlotsToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(TimeSlotsResponse(timeSlots = slots))
    }

    override suspend fun drivers(): Response<DriversResponse> = Response.success(DriversResponse(drivers = driversToReturn))

    override suspend fun vehicles(): Response<VehiclesResponse> = Response.success(VehiclesResponse(vehicles = vehiclesToReturn))
    override suspend fun createDriver(body: CreateDriverRequest): Response<DriverResponse> =
        Response.success(DriverResponse(driver = DriversResponseDriversInner(id = 99, name = body.name, licensePlate = body.licensePlate, driverType = DriversResponseDriversInner.DriverType.fletero)))

    override suspend fun createSchedule(body: CreateScheduleRequest): Response<ScheduleResponse> {
        createdBody = body
        createResponse?.let { return it }
        return Response.success(ScheduleResponse(schedule = entry(id = 500, slot = body.timeSlot, type = SchedulesResponseSchedulesInner.DriverType.fletero)))
    }

    override suspend fun deleteSchedule(id: Int): Response<Unit> {
        deletedIds.add(id)
        return if (deleteSucceeds) Response.success(Unit) else Response.error(403, EMPTY_BODY)
    }

    /**
     * The update the edit screen sends, recorded rather than thrown away.
     *
     * The body is the point of this call: a double that answered with a plausible row without keeping the
     * object could not make the claim the tests are about, because what the screen says is the request.
     */
    override suspend fun updateSchedule(id: Int, body: UpdateScheduleRequest): Response<ScheduleResponse> {
        updatedId = id
        updatedBody = body

        if (gateUpdates) {
            val gate = CompletableDeferred<Unit>()
            updateGates += gate
            gate.await()
        }

        // After the gate and not before it, which is the schedule read's own convention: the answer is the
        // state at release time, and the counter below counts only calls that got here.
        updatesThatRanToCompletion++
        updateFailure?.let { throw it }
        updateResponse?.let { return it }

        return Response.success(ScheduleResponse(schedule = updatedRow(id, body.timeSlot)))
    }

    /**
     * The row the server echoes after an update: the entry that was there, carrying the slot just set.
     *
     * The day's own row rather than a fresh one, because that is what the server does -- it re-reads the
     * row it just wrote and projects it like every other schedule -- and the fallback is only for a day
     * this fake was told to fail.
     */
    private fun updatedRow(id: Int, timeSlot: String?): SchedulesResponseSchedulesInner =
        schedulesToReturn?.firstOrNull { it.id == id }
            ?.let { found -> found.copy(timeSlot = timeSlot ?: found.timeSlot) }
            ?: entry(id = id, slot = timeSlot.orEmpty())

    override suspend fun deactivateSchedule(id: Int): Response<SuccessResponse> = error(NOT_USED)

    override suspend fun deactivateBatch(date: String): Response<SuccessResponse> {
        deactivatedBatchDates += date
        return deactivateBatchResponse
    }

    override suspend fun deleteBatch(date: String): Response<Unit> {
        deletedBatchDates += date
        // A real server removes the batch, so the reload that follows a deletion has to see a smaller
        // history; a fake that only recorded the date would hand the reload the same page and hide the
        // step-back from the test that exists to prove it.
        if (deleteBatchResponse.isSuccessful) remainingHistoryItems -= 1
        return deleteBatchResponse
    }
    override suspend fun export(date: String): Response<TextResponse> {
        lastExportDate = date
        val text = exportToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(TextResponse(text = text))
    }

    companion object {
        const val NOT_USED = "this fake does not implement that call; add it when a test needs it"

        /** The server's own default, which is what a page of the live history holds. */
        const val PAGE_SIZE = 30

        /** The day a live history's newest row sits on, counted back from there for the older ones. */
        val LAST_BATCH_DATE: LocalDate = LocalDate.of(2026, 9, 30)

        /** The shape of the server's message, including the line the operator copies. */
        const val EXPORT_TEXT = "Segunda-feira • 21/09/2026\nCarregamento:\n04:00 - Fletero"

        const val CASA_DRIVER_ID = 10
        const val FLETERO_DRIVER_ID = 11
        const val VEHICLE_ID = 20

        val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())

        /** One company driver and one external, which is the pairing the vehicle rule turns on. */
        fun defaultDrivers() = listOf(
            DriversResponseDriversInner(id = CASA_DRIVER_ID, name = "Carlos", licensePlate = null, driverType = DriversResponseDriversInner.DriverType.casa),
            DriversResponseDriversInner(id = FLETERO_DRIVER_ID, name = "Fletero", licensePlate = "ABC1234", driverType = DriversResponseDriversInner.DriverType.fletero),
        )

        fun defaultVehicles() = listOf(
            VehiclesResponseVehiclesInner(id = VEHICLE_ID, description = "Fiorino", licensePlate = "XYZ9876"),
        )

        /** One batch and a one-page pagination, which is the smallest complete answer. */
        fun defaultHistory() = ScheduleHistoryResponse(
            items = listOf(historyItem()),
            pagination = ScheduleHistoryResponsePagination(page = 1, pageSize = 30, total = 1, totalPages = 1),
        )

        /**
         * One batch, carrying both dates the server computes.
         *
         * Both are arguments and the defaults make them differ by one day, which is the ordinary case.
         * A test for the Friday rule passes the same value twice, which is what the server does when
         * the batch date falls on a Friday.
         *
         * `readOnly` is written as `!canEdit` rather than passed in, because that is what the server
         * computes; a fake that could send a contradiction would let a test pass against a combination
         * the backend cannot produce.
         */
        fun historyItem(
            batchDate: String = "2026-09-18",
            loadingDate: String = "2026-09-19",
            totalLoadings: Int = 2,
            isActive: Boolean = true,
            canEdit: Boolean = true,
        ) = ScheduleHistoryResponseItemsInner(
            batchDate = batchDate,
            loadingDate = loadingDate,
            totalLoadings = totalLoadings,
            createdAt = "2026-09-18T10:00:00Z",
            updatedAt = "2026-09-18T10:00:00Z",
            isActive = isActive,
            canEdit = canEdit,
            canDeactivate = true,
            canDelete = true,
            readOnly = !canEdit,
            creator = null,
        )

        /** One entry, with the field list the contract requires spelled out once. */
        fun entry(
            id: Int,
            slot: String,
            type: SchedulesResponseSchedulesInner.DriverType = SchedulesResponseSchedulesInner.DriverType.fletero,
        ) = SchedulesResponseSchedulesInner(
            id = id,
            scheduleDate = "2026-09-18",
            timeSlot = slot,
            driverType = type,
            driverId = id,
            driverName = "driver $id",
            licensePlate = null,
            vehicleId = null,
            vehicleDescription = null,
            vehiclePlate = null,
            userId = 1,
            createdAt = "2026-09-18T10:00:00Z",
            updatedAt = "2026-09-18T10:00:00Z",
            isActive = true,
            readOnly = false,
            canEdit = true,
            canDeactivate = true,
            canDelete = true,
            creator = null,
        )
    }
}

/**
 * The four filters one history call carried, as the API received them.
 *
 * Values are nullable because that is the distinction under test rather than an oversight: null is
 * "the caller asked for no filter" and the empty string is "the caller asked for the empty value".
 * They are different requests -- the server answers the second with a 400 -- and the difference is
 * invisible at the call site, so this is where it has to be visible instead.
 */
data class LoadingHistoryQuery(
    val date: String?,
    val month: String?,
    val page: Int?,
    val pageSize: Int?,
)
