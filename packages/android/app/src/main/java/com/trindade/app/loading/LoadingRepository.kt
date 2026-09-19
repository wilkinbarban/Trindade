package com.trindade.app.loading

import com.trindade.app.contract.models.CreateDriverRequest
import com.trindade.app.contract.models.CreateScheduleRequest
import com.trindade.app.contract.models.DriversResponseDriversInner
import com.trindade.app.contract.models.ScheduleHistoryResponse
import com.trindade.app.contract.models.SchedulesResponseSchedulesInner
import com.trindade.app.contract.models.UpdateScheduleRequest
import com.trindade.app.contract.models.VehiclesResponseVehiclesInner
import com.trindade.app.network.LoadingApi
import com.trindade.app.network.runCatchingCancellable
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
        runCatchingCancellable { api.schedules(date) }.getOrNull()
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
        runCatchingCancellable { api.history(date = date, month = month, page = page, pageSize = pageSize) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    suspend fun timeSlots(): List<String>? =
        runCatchingCancellable { api.timeSlots() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.timeSlots

    suspend fun drivers(): List<DriversResponseDriversInner>? =
        runCatchingCancellable { api.drivers() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.drivers

    suspend fun vehicles(): List<VehiclesResponseVehiclesInner>? =
        runCatchingCancellable { api.vehicles() }.getOrNull()
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
    suspend fun deleteSchedule(id: Int): ScheduleLifecycleResult = lifecycle { api.deleteSchedule(id) }

    /**
     * Deactivates one entry.
     *
     * Answers a SuccessResponse rather than a schedule, so there is nothing to return but whether the
     * server agreed; the screen reloads the date either way and the reload is the real confirmation.
     */
    suspend fun deactivateSchedule(id: Int): Boolean =
        runCatchingCancellable { api.deactivateSchedule(id) }.getOrNull()?.isSuccessful == true

    /**
     * Deactivates every entry of one batch, which is the day the history groups by.
     *
     * The refusal is a business answer here, not a network fault: 403 is the administrator-only route
     * and 404 is a batch that is already gone, and the history screen says each in its own words. The
     * boolean this used to answer could not carry that, which is why the two batch calls and the
     * single-entry delete now share one result type.
     */
    suspend fun deactivateBatch(date: String): ScheduleLifecycleResult = lifecycle { api.deactivateBatch(date) }

    /** Removes every entry of one batch. See [deactivateBatch] for the refusal split. */
    suspend fun deleteBatch(date: String): ScheduleLifecycleResult = lifecycle { api.deleteBatch(date) }

    /**
     * The single reading of the three calls that change a schedule's life.
     *
     * Deactivating answers 200 with a `SuccessResponse` and deleting answers 204 with no body at all,
     * and none of those bodies is read here. They are one answer to the caller's only question -- did
     * the server agree -- and the difference between a flag and an empty body is not a distinction any
     * screen can act on: there is nothing to show and nothing to do differently.
     */
    private suspend fun lifecycle(call: suspend () -> retrofit2.Response<*>): ScheduleLifecycleResult {
        val response = runCatchingCancellable { call() }.getOrNull() ?: return ScheduleLifecycleResult.Unreachable
        return if (response.isSuccessful) {
            ScheduleLifecycleResult.Changed
        } else {
            ScheduleLifecycleResult.Refused(response.code())
        }
    }

    /** The WhatsApp text for one date, or null when the server cannot render it. */
    suspend fun exportText(date: String): String? =
        runCatchingCancellable { api.export(date) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.text

    /** Quick-add, which always makes a `fletero`: company drivers come from the admin surface. */
    suspend fun createDriver(name: String, licensePlate: String?): DriversResponseDriversInner? =
        runCatchingCancellable { api.createDriver(CreateDriverRequest(name = name, licensePlate = licensePlate)) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.driver

    private suspend fun scheduleWrite(
        call: suspend () -> retrofit2.Response<com.trindade.app.contract.models.ScheduleResponse>,
    ): ScheduleWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull() ?: return ScheduleWriteResult.Unreachable
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

/**
 * What a call that changes a schedule's life produced: one entry deleted, or a whole batch
 * deactivated or deleted.
 *
 * One type for all three because they are one question to a caller -- did the server agree, and if
 * not, why -- and `Changed` covers every success because the difference between a 200 carrying a flag
 * and a 204 carrying nothing is not something any screen can act on. The status is carried for the
 * same reason [ScheduleWriteResult] carries it: the two refusals need different words from the
 * operator, and "it failed" would leave both without anything to do.
 *
 * A 403 on the single-entry route means a Trabalhador tried to delete an entry that is not their own;
 * on the batch routes it means the administrator-only check, and `canDelete` overstates a Trabalhador
 * there, so the history draws an action whose answer is this 403. 404 is a batch that is already gone.
 */
sealed interface ScheduleLifecycleResult {
    data object Changed : ScheduleLifecycleResult
    data class Refused(val statusCode: Int) : ScheduleLifecycleResult
    data object Unreachable : ScheduleLifecycleResult
}
