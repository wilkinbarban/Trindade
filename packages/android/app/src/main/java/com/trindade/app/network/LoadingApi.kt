package com.trindade.app.network

import com.trindade.app.contract.models.CreateDriverRequest
import com.trindade.app.contract.models.CreateScheduleRequest
import com.trindade.app.contract.models.DriverResponse
import com.trindade.app.contract.models.DriversResponse
import com.trindade.app.contract.models.ScheduleHistoryResponse
import com.trindade.app.contract.models.ScheduleResponse
import com.trindade.app.contract.models.SchedulesResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.TextResponse
import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateScheduleRequest
import com.trindade.app.contract.models.VehiclesResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The loading schedule surface.
 *
 * Written by hand like the others. A method arrives with the screen that calls it rather than with
 * the contract that defines it: an endpoint nothing calls compiles without proving anything about the
 * contract it claims to speak.
 *
 * Two things about this surface are not obvious from the signatures and are worth stating where the
 * caller will see them. The fletero limit is **informative**: the server accepts a fourth and the
 * counter is a display, so nothing here returns an error for exceeding it. And a schedule belongs to
 * a DATE rather than to a week, so the date is an argument on the reads and a field on the writes.
 */
interface LoadingApi {

    @GET("api/loading/schedules")
    suspend fun schedules(@Query("date") date: String): Response<SchedulesResponse>

    /**
     * One page of the loading history, grouped by batch date and filtered by one date, one month, or
     * neither.
     *
     * The four filters are nullable because absent and empty are different requests to this server.
     * Retrofit omits a null `@Query` and sends an empty one as `date=`, and the server validates
     * `date` against `^\d{4}-\d{2}-\d{2}$` in `loading.schema.ts`, so an empty string is a 400
     * answering a client that meant "no filter". A null `page` or `pageSize` asks for the server's
     * default -- 1 and 30 -- rather than for zero; `pageSize` is capped at 100.
     *
     * Each item carries two dates and they are not the same day. `batch_date` is the
     * `schedule_date` the entries were created under, which is also the grouping key; `loading_date`
     * is the load's own date. The server computes the second as `batch_date` plus one day, except on
     * a Friday, where `loading.service.ts` answers `batch_date` itself -- so the two coincide on a
     * Friday batch and are a day apart on every other one. A screen that shows the batch date as the
     * loading day is wrong on every day except Friday.
     *
     * That value is the server's and not this client's to recompute. The export surface labels a
     * Friday batch with the following Monday instead, so for that batch the loading date this read
     * answers and the date the export names are not the same number.
     *
     * The five lifecycle flags -- `isActive`, `readOnly`, `canEdit`, `canDeactivate`, `canDelete` --
     * are the server's own projection, from `projectHistoryPermissions`, which is the only party that
     * knows the caller's role and where the one-hour edit window has landed; `readOnly` there is
     * exactly `!canEdit`. All five are required in the contract here, unlike the report history's.
     *
     * Each item is typed `ScheduleHistoryResponseItemsInner`, and the document also declares a
     * `LoadingBatchHistoryItem` component that nothing references. The second is the name this
     * endpoint looks like it should use and does not.
     */
    @GET("api/loading/schedules/history")
    suspend fun history(
        @Query("date") date: String?,
        @Query("month") month: String?,
        @Query("page") page: Int?,
        @Query("pageSize") pageSize: Int?,
    ): Response<ScheduleHistoryResponse>

    /** The configured slots, read from settings with the built-in daily list as a fallback. */
    @GET("api/loading/time-slots")
    suspend fun timeSlots(): Response<TimeSlotsResponse>

    @GET("api/loading/drivers")
    suspend fun drivers(): Response<DriversResponse>

    /** Always creates a `fletero`; company drivers are managed from the admin surface. */
    @POST("api/loading/drivers")
    suspend fun createDriver(@Body body: CreateDriverRequest): Response<DriverResponse>

    @GET("api/loading/vehicles")
    suspend fun vehicles(): Response<VehiclesResponse>

    @POST("api/loading/schedules")
    suspend fun createSchedule(@Body body: CreateScheduleRequest): Response<ScheduleResponse>

    @PATCH("api/loading/schedules/{id}")
    suspend fun updateSchedule(@Path("id") id: Int, @Body body: UpdateScheduleRequest): Response<ScheduleResponse>

    @DELETE("api/loading/schedules/{id}")
    suspend fun deleteSchedule(@Path("id") id: Int): Response<Unit>

    @PATCH("api/loading/schedules/{id}/deactivate")
    suspend fun deactivateSchedule(@Path("id") id: Int): Response<SuccessResponse>

    @PATCH("api/loading/schedules/batch/{date}/deactivate")
    suspend fun deactivateBatch(@Path("date") date: String): Response<SuccessResponse>

    @DELETE("api/loading/schedules/batch/{date}")
    suspend fun deleteBatch(@Path("date") date: String): Response<Unit>

    /**
     * The WhatsApp text for one date, rendered by the server.
     *
     * Never assembled on the client. The text is the product's output rather than a view of the data,
     * and a second implementation here would be a second thing to keep in agreement with the first.
     */
    @GET("api/loading/export")
    suspend fun export(@Query("date") date: String): Response<TextResponse>
}
