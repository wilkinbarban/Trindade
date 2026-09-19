package com.trindade.app.loading

import com.trindade.app.contract.models.CreateDriverRequest
import com.trindade.app.contract.models.CreateScheduleRequest
import com.trindade.app.contract.models.DriversResponseDriversInner
import com.trindade.app.contract.models.ScheduleHistoryResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.contract.models.UpdateScheduleRequest
import com.trindade.app.contract.models.VehiclesResponseVehiclesInner
import com.trindade.app.network.LoadingApi
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The loading schedule data layer.
 *
 * Reads answer null and writes answer a result, the same split as the reports layer and for the same
 * reason: a refusal on a write is a business answer the screen has to explain, while an absent read is
 * something the screen can retry or report.
 *
 * Nothing here enforces the three-fletero limit. That limit is informative by an explicit business
 * decision -- the server accepts a fourth assignment and the counter is a display -- so a client that
 * refused it would be inventing a rule the product deliberately does not have. The counter belongs in
 * the screen, as an indication.
 */
@Singleton
class LoadingRepository @Inject constructor(
    private val api: LoadingApi,
) {

    suspend fun schedules(date: String): List<SchedulesResponseSchedulesInner>? =
        runCatching { api.schedules(date) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.schedules

    /**
     * One page of the loading history, or null when the server cannot be asked.
     *
     * The envelope rather than an unwrapped list like the reads above, because the pagination is half
     * the answer. A list alone leaves the screen unable to tell a full page from the last one, so it
     * cannot know whether to offer another page, and it cannot show a total. `date` and `month` are
     * nullable on purpose and pass through as they arrived: null means the caller asked for no filter
     * and the empty string means the caller asked for the empty value, which the server refuses with
     * a 400. A null `page` or `pageSize` takes the server's default.
     *
     * Each item's `loadingDate` is the server's own computation and is passed on untouched; this
     * layer does not re-derive it from `batchDate`, because the rule belongs to the server and a
     * second implementation here would be a second thing to keep in agreement with the first.
     */
    suspend fun history(
        date: String?,
        month: String?,
        page: Int?,
        pageSize: Int?,
    ): ScheduleHistoryResponse? =
        runCatching { api.history(date = date, month = month, page = page, pageSize = pageSize) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    suspend fun timeSlots(): List<String>? =
        runCatching { api.timeSlots() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.timeSlots

    suspend fun drivers(): List<DriversResponseDriversInner>? =
        runCatching { api.drivers() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.drivers

    suspend fun vehicles(): List<VehiclesResponseVehiclesInner>? =
        runCatching { api.vehicles() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.vehicles

    suspend fun createSchedule(body: CreateScheduleRequest): ScheduleWriteResult =
        scheduleWrite { api.createSchedule(body) }

    suspend fun updateSchedule(id: Int, body: UpdateScheduleRequest): ScheduleWriteResult =
        scheduleWrite { api.updateSchedule(id, body) }

    /**
     * Removes one entry. A 403 here means the caller may only delete their own, which the screen must
     * say rather than report as a general failure.
     */
    suspend fun deleteSchedule(id: Int): ScheduleDeleteResult {
        val response = runCatching { api.deleteSchedule(id) }.getOrNull()
            ?: return ScheduleDeleteResult.Unreachable
        return if (response.isSuccessful) ScheduleDeleteResult.Deleted
        else ScheduleDeleteResult.Refused(response.code())
    }

    /**
     * Deactivates one entry.
     *
     * Answers a SuccessResponse rather than a schedule, so there is nothing to return but whether the
     * server agreed; the screen reloads the date either way and the reload is the real confirmation.
     */
    suspend fun deactivateSchedule(id: Int): Boolean =
        runCatching { api.deactivateSchedule(id) }.getOrNull()?.isSuccessful == true

    suspend fun deactivateBatch(date: String): Boolean =
        runCatching { api.deactivateBatch(date) }.getOrNull()?.isSuccessful == true

    suspend fun deleteBatch(date: String): Boolean =
        runCatching { api.deleteBatch(date) }.getOrNull()?.isSuccessful == true

    /** The WhatsApp text for one date, or null when the server cannot render it. */
    suspend fun exportText(date: String): String? =
        runCatching { api.export(date) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.text

    /** Quick-add, which always makes a `fletero`: company drivers come from the admin surface. */
    suspend fun createDriver(name: String, licensePlate: String?): DriversResponseDriversInner? =
        runCatching { api.createDriver(CreateDriverRequest(name = name, licensePlate = licensePlate)) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.driver

    private suspend fun scheduleWrite(
        call: suspend () -> retrofit2.Response<com.trindade.app.contract.models.ScheduleResponse>,
    ): ScheduleWriteResult {
        val response = runCatching { call() }.getOrNull() ?: return ScheduleWriteResult.Unreachable
        val schedule = response.body()?.schedule
        if (response.isSuccessful && schedule != null) return ScheduleWriteResult.Saved(schedule)
        return ScheduleWriteResult.Refused(response.code())
    }
}

sealed interface ScheduleWriteResult {
    data class Saved(val schedule: SchedulesResponseSchedulesInner) : ScheduleWriteResult
    data class Refused(val statusCode: Int) : ScheduleWriteResult
    data object Unreachable : ScheduleWriteResult
}

sealed interface ScheduleDeleteResult {
    data object Deleted : ScheduleDeleteResult

    /** 403 means a Trabalhador tried to delete someone else's entry, which is worth saying. */
    data class Refused(val statusCode: Int) : ScheduleDeleteResult
    data object Unreachable : ScheduleDeleteResult
}
