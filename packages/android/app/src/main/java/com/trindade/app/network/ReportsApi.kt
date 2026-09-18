package com.trindade.app.network

import com.trindade.app.contract.models.CategoriesResponse
import com.trindade.app.contract.models.CreateReportRequest
import com.trindade.app.contract.models.PhotoResponse
import com.trindade.app.contract.models.PhotosResponse
import com.trindade.app.contract.models.ProductsResponse
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
 * client. The history operations are deliberately absent — they belong to the history screen, and an
 * endpoint nothing calls compiles without proving anything about the contract it claims to speak.
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
