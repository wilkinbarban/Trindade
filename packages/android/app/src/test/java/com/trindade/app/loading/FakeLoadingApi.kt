package com.trindade.app.loading

import com.trindade.app.contract.models.CreateDriverRequest
import com.trindade.app.contract.models.CreateScheduleRequest
import com.trindade.app.contract.models.DriverResponse
import com.trindade.app.contract.models.DriversResponse
import com.trindade.app.contract.models.ScheduleResponse
import com.trindade.app.contract.models.SchedulesResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.TextResponse
import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateScheduleRequest
import com.trindade.app.contract.models.VehiclesResponse
import com.trindade.app.network.LoadingApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * A `LoadingApi` that answers from memory, like the reports one and for the same reason: the interface
 * is a plain Kotlin interface, so the repository under test is the real one and only the network is
 * replaced.
 *
 * The methods D4c and D4d will need throw rather than returning a plausible default, so a test cannot
 * pass while calling something it never meant to.
 */
class FakeLoadingApi(
    private val schedulesToReturn: List<SchedulesResponseSchedulesInner>? = emptyList(),
    private val timeSlotsToReturn: List<String>? = listOf("04:00", "04:30", "05:00"),
) : LoadingApi {

    var lastSchedulesDate: String? = null
        private set

    override suspend fun schedules(date: String): Response<SchedulesResponse> {
        lastSchedulesDate = date
        val schedules = schedulesToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(SchedulesResponse(schedules = schedules))
    }

    override suspend fun timeSlots(): Response<TimeSlotsResponse> {
        val slots = timeSlotsToReturn ?: return Response.error(500, EMPTY_BODY)
        return Response.success(TimeSlotsResponse(timeSlots = slots))
    }

    override suspend fun drivers(): Response<DriversResponse> = error(NOT_USED)
    override suspend fun vehicles(): Response<VehiclesResponse> = error(NOT_USED)
    override suspend fun createDriver(body: CreateDriverRequest): Response<DriverResponse> = error(NOT_USED)
    override suspend fun createSchedule(body: CreateScheduleRequest): Response<ScheduleResponse> = error(NOT_USED)
    override suspend fun updateSchedule(id: Int, body: UpdateScheduleRequest): Response<ScheduleResponse> = error(NOT_USED)
    override suspend fun deleteSchedule(id: Int): Response<Unit> = error(NOT_USED)
    override suspend fun deactivateSchedule(id: Int): Response<SuccessResponse> = error(NOT_USED)
    override suspend fun deactivateBatch(date: String): Response<SuccessResponse> = error(NOT_USED)
    override suspend fun deleteBatch(date: String): Response<Unit> = error(NOT_USED)
    override suspend fun export(date: String): Response<TextResponse> = error(NOT_USED)

    companion object {
        const val NOT_USED = "this fake does not implement that call; add it when a test needs it"

        val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())

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
