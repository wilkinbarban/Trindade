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
    /** Null makes the history call fail, the same convention as [schedulesToReturn] and for the same reason. */
    private val historyToReturn: ScheduleHistoryResponse? = defaultHistory(),
    private val timeSlotsToReturn: List<String>? = listOf("04:00", "04:30", "05:00"),
    private val driversToReturn: List<DriversResponseDriversInner> = defaultDrivers(),
    private val vehiclesToReturn: List<VehiclesResponseVehiclesInner> = defaultVehicles(),
    private val createResponse: Response<ScheduleResponse>? = null,
    private val deleteSucceeds: Boolean = true,
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

    var lastExportDate: String? = null
        private set

    /** What the last create carried, which is how the payload rules below are asserted. */
    var createdBody: CreateScheduleRequest? = null
        private set

    var deletedIds = mutableListOf<Int>()
        private set

    override suspend fun schedules(date: String): Response<SchedulesResponse> {
        lastSchedulesDate = date
        val schedules = schedulesToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(SchedulesResponse(schedules = schedules))
    }

    override suspend fun history(
        date: String?,
        month: String?,
        page: Int?,
        pageSize: Int?,
    ): Response<ScheduleHistoryResponse> {
        lastHistoryQuery = LoadingHistoryQuery(date = date, month = month, page = page, pageSize = pageSize)
        val history = historyToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(history)
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

    override suspend fun updateSchedule(id: Int, body: UpdateScheduleRequest): Response<ScheduleResponse> = error(NOT_USED)
    override suspend fun deactivateSchedule(id: Int): Response<SuccessResponse> = error(NOT_USED)
    override suspend fun deactivateBatch(date: String): Response<SuccessResponse> = error(NOT_USED)
    override suspend fun deleteBatch(date: String): Response<Unit> = error(NOT_USED)
    override suspend fun export(date: String): Response<TextResponse> {
        lastExportDate = date
        val text = exportToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(TextResponse(text = text))
    }

    companion object {
        const val NOT_USED = "this fake does not implement that call; add it when a test needs it"

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
