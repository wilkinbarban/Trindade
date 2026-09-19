package com.trindade.app.reports

import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.CreateReportRequest
import com.trindade.app.contract.models.PhotosResponsePhotosInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportHistoryResponse
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.contract.models.UpdateReportRequest
import com.trindade.app.network.ReportsApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The reports data layer.
 *
 * Every call answers null rather than throwing when the server refuses or cannot be reached, because
 * the screens above decide what to say and a repository is the wrong place to hold that copy. The one
 * exception is a write that the server rejects: that is a business answer -- the report is past its
 * edit window, the payload is invalid -- and the caller needs to tell those apart from a network
 * failure, so it comes back as a result rather than as absence.
 *
 * The element types a task can declare are `check`, `check_assai`, `check_normal` and `temperature`.
 * They are not in the contract as an enum: the document types `task_type` as a plain string, so the
 * set lives in the server's schema and in the SPA's renderer, and this client reads it from the task
 * it is given rather than deciding what is valid.
 */
@Singleton
class ReportsRepository @Inject constructor(
    private val api: ReportsApi,
) {

    /**
     * The two product offers, or null when the server cannot be asked.
     *
     * Null here is a real absence with a consequence: the generator cannot render a product-check task
     * without knowing what to offer, so a caller has to decide whether to wait, retry, or say so
     * rather than render an empty checklist that looks like "no products available".
     */
    suspend fun productOffers(): ProductsResponse? =
        runCatchingCancellable { api.products() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    /**
     * The active categories with their tasks, in the order the server sends them.
     *
     * The element type in this response is named `CategoriesResponseCategoriesInner`, not
     * `ReportCategory`, and that is a contract defect rather than a choice: the response declares its
     * array items inline instead of referencing the `ReportCategory` component it already has, so the
     * first client to generate from it gets a second, worse-named type for the same shape. The same
     * defect was fixed at the top level of the document and survives one level down. It is noted here
     * because this is where the next reader will meet it.
     */
    suspend fun categories(): List<CategoriesResponseCategoriesInner>? =
        runCatchingCancellable { api.categories() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.categories

    /**
     * The shift the server detects, or null when it cannot be asked.
     *
     * Null is not "no shift": it means the question was not answered, and the generator has to
     * distinguish that from the server saying the current time is outside every shift.
     */
    suspend fun detectedTurno(): String? =
        runCatchingCancellable { api.turno() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.turno
            ?.value

    /**
     * One page of the report history, or null when the server cannot be asked.
     *
     * The envelope rather than an unwrapped list like the reads above, because the pagination is half
     * the answer. A list alone leaves the screen unable to tell a full page from the last one, so it
     * cannot know whether to offer another page, and it cannot show a total. `date` and `month` are
     * nullable on purpose and pass through as they arrived: null means the caller asked for no filter
     * and the empty string means the caller asked for the empty value, which the server refuses with
     * a 400. A null `page` or `pageSize` takes the server's default.
     */
    suspend fun history(
        date: String?,
        month: String?,
        page: Int?,
        pageSize: Int?,
    ): ReportHistoryResponse? =
        runCatchingCancellable { api.history(date = date, month = month, page = page, pageSize = pageSize) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    /**
     * Retires a report, or says why the server would not.
     *
     * The failure split is the same one the writes above make: a refusal is a business answer the
     * screen has to explain -- this caller may not do it, or the report is already gone -- while an
     * unreachable server is something to retry. Nothing below the screen decides which refusal means
     * what, because the two refusals arrive as different status codes and only the screen knows what
     * to say about each.
     */
    suspend fun deactivate(id: Int): ReportLifecycleResult = lifecycle { api.deactivate(id) }

    /** Removes a report. See [deactivate] for the failure split, which is deliberately the same one. */
    suspend fun delete(id: Int): ReportLifecycleResult = lifecycle { api.delete(id) }

    /**
     * The single reading of both lifecycle calls.
     *
     * Deactivating answers 200 with a `SuccessResponse` and deleting answers 204 with no body at all,
     * and neither body is read here. Those two are the same answer to the caller's only question -- did
     * the server agree -- and the difference between a flag and an empty body is not a distinction the
     * screen can act on: there is nothing to show and nothing to do differently. A repository that
     * modelled them separately would hand the screen two types for one decision.
     */
    private suspend fun lifecycle(call: suspend () -> retrofit2.Response<*>): ReportLifecycleResult {
        val response = runCatchingCancellable { call() }.getOrNull() ?: return ReportLifecycleResult.Unreachable
        return if (response.isSuccessful) {
            ReportLifecycleResult.Changed
        } else {
            ReportLifecycleResult.Refused(response.code())
        }
    }

    suspend fun report(id: Int): ReportResponseReport? =
        runCatchingCancellable { api.report(id) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.report

    suspend fun create(body: CreateReportRequest): ReportWriteResult = submit { api.createReport(body) }

    suspend fun update(id: Int, body: UpdateReportRequest): ReportWriteResult =
        submit { api.updateReport(id, body) }

    /**
     * The WhatsApp text, rendered by the server, or null when it cannot be fetched.
     *
     * Never assembled on the client: the text is the product's output rather than a view of the data,
     * and a second implementation would be a second thing to keep in agreement with the first.
     */
    suspend fun exportText(id: Int): String? =
        runCatchingCancellable { api.export(id) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.text

    /**
     * Attaches a compressed photo.
     *
     * The bytes are expected to be JPEG already, and the part's content type says so. The server
     * sniffs the content and answers 415 when the declared type does not match, so claiming JPEG for
     * something else fails at the door rather than storing a file nothing can render.
     */
    suspend fun attachPhoto(reportId: Int, jpeg: ByteArray): PhotoAttachResult {
        val part = MultipartBody.Part.createFormData(
            name = "file",
            filename = "photo.jpg",
            body = jpeg.toRequestBody("image/jpeg".toMediaType()),
        )

        val response = runCatchingCancellable { api.attachPhoto(reportId, part) }.getOrNull()
            ?: return PhotoAttachResult.Unreachable
        val photo = response.body()?.photo
        if (response.isSuccessful && photo != null) return PhotoAttachResult.Saved(photo)
        return PhotoAttachResult.Refused(response.code())
    }

    /** The photos already attached, or null when the server cannot be asked. */
    suspend fun photos(reportId: Int): List<PhotosResponsePhotosInner>? =
        runCatchingCancellable { api.photos(reportId) }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.photos

    /** Removes one photo. [ReportWriteResult] is not reused because a photo has no report to return. */
    suspend fun deletePhoto(photoId: Int): PhotoDeleteResult {
        val response = runCatchingCancellable { api.deletePhoto(photoId) }.getOrNull()
            ?: return PhotoDeleteResult.Unreachable
        return if (response.isSuccessful) PhotoDeleteResult.Deleted else PhotoDeleteResult.Refused(response.code())
    }

    private suspend fun submit(call: suspend () -> retrofit2.Response<com.trindade.app.contract.models.ReportResponse>): ReportWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull() ?: return ReportWriteResult.Unreachable
        val report = response.body()?.report
        if (response.isSuccessful && report != null) return ReportWriteResult.Saved(report)
        return ReportWriteResult.Refused(response.code())
    }
}

/**
 * What a write produced.
 *
 * [Refused] carries the status because the two refusals a caller must tell apart arrive as different
 * codes: 403 is the edit window, 400 is the payload. A single "failed" would leave the form unable to
 * say which, and it would say "try again" for a report that can never be edited again.
 */
sealed interface ReportWriteResult {
    data class Saved(val report: ReportResponseReport) : ReportWriteResult
    data class Refused(val statusCode: Int) : ReportWriteResult
    data object Unreachable : ReportWriteResult
}

/**
 * What a lifecycle call produced: a deactivation or a deletion.
 *
 * One type for both because they are one question to a caller -- did the server agree, and if not,
 * why -- and `Changed` covers both successes because the difference between a 200 carrying a flag and
 * a 204 carrying nothing is not something any screen can act on. The status is carried for the same
 * reason [ReportWriteResult] carries it: 403 and 404 need different sentences from the operator, and
 * "it failed" would leave both without anything to do.
 */
sealed interface ReportLifecycleResult {
    data object Changed : ReportLifecycleResult
    data class Refused(val statusCode: Int) : ReportLifecycleResult
    data object Unreachable : ReportLifecycleResult
}

/**
 * What attaching a photo produced.
 *
 * The status is carried because two refusals need different words from the operator: 413 means this
 * photo is too large and another should be taken, while 409 means the report already carries five and
 * one has to be removed first. "It failed" would leave both without anything to do.
 */
sealed interface PhotoAttachResult {
    data class Saved(val photo: PhotosResponsePhotosInner) : PhotoAttachResult
    data class Refused(val statusCode: Int) : PhotoAttachResult
    data object Unreachable : PhotoAttachResult
}

sealed interface PhotoDeleteResult {
    data object Deleted : PhotoDeleteResult
    data class Refused(val statusCode: Int) : PhotoDeleteResult
    data object Unreachable : PhotoDeleteResult
}
