package com.trindade.app.network

import com.trindade.app.contract.models.CreateDriverRequest
import com.trindade.app.contract.models.CreateScheduleRequest
import com.trindade.app.contract.models.DriverResponse
import com.trindade.app.contract.models.DriversResponse
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
 * Written by hand like the others. The history operations are deliberately absent -- they belong to
 * the history screen, and an endpoint nothing calls compiles without proving anything about the
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
