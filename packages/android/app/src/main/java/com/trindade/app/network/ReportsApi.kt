package com.trindade.app.network

import com.trindade.app.contract.models.CategoriesResponse
import com.trindade.app.contract.models.CreateReportRequest
import com.trindade.app.contract.models.PhotoResponse
import com.trindade.app.contract.models.PhotosResponse
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportHistoryResponse
import com.trindade.app.contract.models.ReportResponse
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.TextResponse
import com.trindade.app.contract.models.TurnoResponse
import com.trindade.app.contract.models.UpdateReportRequest
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * The reports surface, as far as this client needs it.
 *
 * Written by hand like [AuthApi], and narrow for the same reasons: a generated client would expose
 * every operation including the admin ones, and it would fix a call shape that belongs to this
 * client. A method arrives with the screen that calls it rather than with the contract that defines
 * it: an endpoint nothing calls compiles without proving anything about the contract it claims to
 * speak.
 *
 * Every call returns [Response] so a caller can tell a refusal from an absence, which matters here
 * because the same status can mean different things: a 403 on a write is the server saying the report
 * is past its edit window, and the form has to say so rather than failing silently.
 */
interface ReportsApi {

    /**
     * The active categories with their tasks, which is what the generator is built from.
     *
     * Each task carries `task_type`, one of `check`, `check_assai`, `check_normal` or `temperature`,
     * and `temperature_readings`, the number of readings a temperature task expects.
     */
    @GET("api/reports/categories")
    suspend fun categories(): Response<CategoriesResponse>

    /**
     * The two product offers, one per product-check element type.
     *
     * An offer and not a constraint: a report stores any product name, and this is the list a client
     * puts in front of an operator. Served by the API so this client does not duplicate the constants
     * the web client keeps.
     */
    @GET("api/reports/products")
    suspend fun products(): Response<ProductsResponse>

    /**
     * The shift the server detects from its own clock.
     *
     * Asked rather than computed: the shift depends on the time of day in São Paulo, the device's
     * clock and timezone are not trustworthy in a yard, and the server is the only party that can
     * answer this the same way twice.
     */
    @GET("api/reports/turno")
    suspend fun turno(): Response<TurnoResponse>

    /**
     * One page of the report history, filtered by one date, one month, or neither.
     *
     * The four filters are nullable because absent and empty are different requests to this server.
     * Retrofit omits a null `@Query` and sends an empty one as `date=`, and the server validates
     * `date` against `^\d{4}-\d{2}-\d{2}$` in `reports.schema.ts`, so an empty string is a 400
     * answering a client that meant "no filter". A null `page` or `pageSize` asks for the server's
     * default -- 1 and 30 -- rather than for zero; `pageSize` is capped at 100.
     *
     * The five lifecycle flags on each item -- `isActive`, `readOnly`, `canEdit`, `canDeactivate`,
     * `canDelete` -- are the server's own projection, from `projectHistoryPermissions`, which is the
     * only party that knows the caller's role and where the edit window has landed; `readOnly` there
     * is exactly `!canEdit`. Those five are optional in the contract while the handler always emits
     * them, so the generated type carries them as `Boolean? = null`, and a caller tests
     * `canEdit == true` instead of reading a null as permission.
     *
     * Two naming traps come with this response and neither of them is about this endpoint. Its items
     * are typed `ReportsResponseReportsInner` and its pagination `ScheduleHistoryResponsePagination`,
     * because the document inlines both schemas here and each one is identical to the reports list's
     * items and to the loading history's pagination -- one class was emitted per shape and named after
     * the other place it appears. And the document also declares a `ReportListItem` component that
     * nothing references, which is the type this endpoint looks like it should use and does not.
     */
    @GET("api/reports/history")
    suspend fun history(
        @Query("date") date: String?,
        @Query("month") month: String?,
        @Query("page") page: Int?,
        @Query("pageSize") pageSize: Int?,
    ): Response<ReportHistoryResponse>

    /**
     * Retires a report without losing it: it stays in the history with `isActive` false.
     *
     * `PATCH` and no body, because the state change is the server's and this client has nothing to
     * add to it. Answers 200 with a `SuccessResponse`, and can refuse with 403 for a caller whose role
     * may not do it and 404 for a report that is gone. The caller's own ability is the item's
     * `canDeactivate`, which the server computes for the caller's role; this client never decides it
     * from a role of its own, because it does not have one.
     */
    @PATCH("api/reports/{id}/deactivate")
    suspend fun deactivate(@Path("id") id: Int): Response<SuccessResponse>

    /**
     * Removes a report for good.
     *
     * Answers 204 with no body at all, which is why the response type is `Unit`: this is the one call
     * here whose success carries nothing to read, and a caller that reached for a body would find
     * null. It can refuse with 403 and 404 the way the deactivate does.
     *
     * The ability is the item's `canDelete`, and that flag overstates what this route accepts: the
     * server's own projection grants `canDelete` to a `Trabalhador` while the route checks for
     * `Administrador` and answers 403, so the client follows the flag it is given and gives that
     * refusal its own sentence instead of hardcoding a role. Anything else would be this client
     * inventing a permission rule, and a second one to keep in agreement with the server's.
     */
    @DELETE("api/reports/{id}")
    suspend fun delete(@Path("id") id: Int): Response<Unit>

    @GET("api/reports/{id}")
    suspend fun report(@Path("id") id: Int): Response<ReportResponse>

    @POST("api/reports")
    suspend fun createReport(@Body body: CreateReportRequest): Response<ReportResponse>

    @PATCH("api/reports/{id}")
    suspend fun updateReport(@Path("id") id: Int, @Body body: UpdateReportRequest): Response<ReportResponse>

    /**
     * The WhatsApp text, rendered by the server.
     *
     * Never assembled on the client. The text is part of the product's output rather than a display
     * of the data, and a second implementation here would be a second thing to keep in agreement with
     * the first.
     */
    @GET("api/reports/{id}/export")
    suspend fun export(@Path("id") id: Int): Response<TextResponse>

    @GET("api/reports/{id}/photos")
    suspend fun photos(@Path("id") id: Int): Response<PhotosResponse>

    /**
     * Attach one photo.
     *
     * Multipart with the file in a field named `file`, which is what the server reads. The declared
     * content type has to match the actual bytes -- the server sniffs the content and rejects a
     * mismatch with 415 -- so the part's type comes from the compressor's output rather than from the
     * file's extension.
     */
    @Multipart
    @POST("api/reports/{id}/photos")
    suspend fun attachPhoto(@Path("id") id: Int, @Part file: MultipartBody.Part): Response<PhotoResponse>

    @DELETE("api/reports/photos/{photoId}")
    suspend fun deletePhoto(@Path("photoId") photoId: Int): Response<SuccessResponse>
}
