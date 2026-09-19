package com.trindade.app.reports

import com.trindade.app.contract.models.CategoriesResponse
import com.trindade.app.contract.models.CategoriesResponseCategoriesInner
import com.trindade.app.contract.models.CreateReportRequest
import com.trindade.app.contract.models.PhotoResponse
import com.trindade.app.contract.models.PhotosResponse
import com.trindade.app.contract.models.PhotosResponsePhotosInner
import com.trindade.app.contract.models.ProductsResponse
import com.trindade.app.contract.models.ReportCategoryTasksInner
import com.trindade.app.contract.models.ReportDetailItemsInner
import com.trindade.app.contract.models.ReportDetailTemperaturesInner
import com.trindade.app.contract.models.ReportHistoryResponse
import com.trindade.app.contract.models.ReportListItemUser
import com.trindade.app.contract.models.ReportResponse
import com.trindade.app.contract.models.ReportResponseReport
import com.trindade.app.contract.models.ReportsResponseReportsInner
import com.trindade.app.contract.models.ScheduleHistoryResponsePagination
import com.trindade.app.contract.models.SuccessResponse
import com.trindade.app.contract.models.TextResponse
import com.trindade.app.contract.models.TurnoResponse
import com.trindade.app.contract.models.UpdateReportRequest
import kotlinx.coroutines.CompletableDeferred
import com.trindade.app.network.ReportsApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * A `ReportsApi` that answers from memory.
 *
 * The interfaces Retrofit implements are plain Kotlin interfaces, so the repository under test is the
 * real one and only the network is replaced. No mocking library, and no test that passes because a
 * stub agreed with itself.
 *
 * Methods the tests do not exercise throw rather than return a plausible default: a quiet default is
 * how a test passes while calling something it never meant to, and the throw names exactly what will
 * have to be filled in when a test needs it.
 *
 * Enum values are taken from `entries` rather than named. The generator emits its constants with names
 * derived from the wire values, and hardcoding one here would be a second place to be wrong about what
 * the contract says.
 */
class FakeReportsApi(
    private val categories: List<CategoriesResponseCategoriesInner> = defaultCategories(),
    private val offers: ProductsResponse = defaultOffers(),
    /** Null makes the shift call fail, which is the only way a caller sees it as undetectable. */
    private val turnoValue: String? = DEFAULT_TURNO,
    private val createResponse: Response<ReportResponse> = Response.success(ReportResponse(report = createdReport())),
    private val reportToReturn: ReportResponseReport? = createdReport(),
    /** Null makes the history call fail, the same convention as [turnoValue] and for the same reason. */
    private val historyToReturn: ReportHistoryResponse? = defaultHistory(),
    /**
     * When set, the history is this many reports at the server's default page size, and the lifecycle
     * calls really change it: a deletion removes one and a deactivation does not.
     *
     * A fixed [historyToReturn] cannot answer a paging test -- asking for page 2 and being handed page 1
     * is exactly the mistake such a test exists to catch -- and it cannot model the case the screen's
     * step-back exists for, where deleting the last report on the last page leaves that page empty.
     */
    private val historyItemCount: Int? = null,
    /**
     * When true, every history call waits on its own gate before answering, so a test can hold two of them
     * in the air at once and choose which one lands last.
     *
     * The gate is released *before* the answer is built, on purpose: the state the fake reads is the state
     * at release time, which is how a test reproduces what the code could not otherwise see -- a stale
     * response arriving after a newer one, carrying rows the operator is no longer asking for.
     */
    private val gateHistory: Boolean = false,
    private val photosToReturn: List<PhotosResponsePhotosInner> = emptyList(),
    private val exportToReturn: String = "CRONOGRAMA DE CARREGAMENTO",
    private val attachResponse: Response<PhotoResponse> = Response.success(PhotoResponse(photo = aPhoto())),
    /**
     * What a deactivation answers. 200 with a body in practice; the fake never reads the body either.
     */
    private val deactivateResponse: Response<SuccessResponse> =
        Response.success(SuccessResponse(success = SuccessResponse.Success.entries.first())),
    /** What a deletion answers, 204 with no body in practice, and the same convention for a refusal. */
    private val deleteResponse: Response<Unit> = Response.success(Unit),
) : ReportsApi {

    /** What the last create carried, which is the whole point of most of these tests. */
    var createdBody: CreateReportRequest? = null
        private set

    /**
     * The filters the last history call carried, all four at once.
     *
     * Grouped rather than four loose fields because the assertion these exist for is about the whole
     * query: an absent filter has to arrive as null and an empty one has to arrive empty, and four
     * separate fields let a test pin three of them and miss the one that moved.
     */
    var lastHistoryQuery: ReportsHistoryQuery? = null
        private set

    /**
     * Every history call, in order, which is what a paging test has to assert on.
     *
     * [lastHistoryQuery] is the last one, and the sequence is the only thing that can show what a
     * step-back did: the pages the view model asked for after a deletion are the behaviour under test,
     * and the state it settled on could have been reached without asking for the dismissed page at all.
     */
    val historyQueries = mutableListOf<ReportsHistoryQuery>()

    /** The report ids the two lifecycle calls were made with, in the order they were made. */
    val deactivatedIds = mutableListOf<Int>()
    val deletedIds = mutableListOf<Int>()

    /** One gate per history call held open by [gateHistory], in the order the calls were made. */
    val historyGates = mutableListOf<CompletableDeferred<Unit>>()

    /**
     * How many reports the live history holds, counting down as deletions succeed.
     *
     * Writable on purpose: a test that wants the cascade the view model's step-back rule is recursive
     * for has to empty more than one page at once, and a deletion cannot do that. Setting this to 0
     * mid-test is how another operator's change is simulated, and it is the same state the fake serves
     * pages from, so the arithmetic under test stays the one the server does.
     */
    var remainingHistoryItems = historyItemCount ?: 0

    override suspend fun categories(): Response<CategoriesResponse> = Response.success(CategoriesResponse(categories))

    override suspend fun products(): Response<ProductsResponse> = Response.success(offers)

    override suspend fun turno(): Response<TurnoResponse> {
        val value = turnoValue ?: return Response.error(500, EMPTY_BODY)
        return Response.success(TurnoResponse(turno = TurnoResponse.Turno.entries.first { it.value == value }))
    }

    override suspend fun createReport(body: CreateReportRequest): Response<ReportResponse> {
        createdBody = body
        return createResponse
    }

    /** How many times an upload was attempted, which is what makes "did not upload" assertable. */
    var attachCalls = 0
        private set

    override suspend fun report(id: Int): Response<ReportResponse> =
        reportToReturn?.let { Response.success(ReportResponse(report = it)) }
            ?: Response.error(404, EMPTY_BODY)

    override suspend fun history(
        date: String?,
        month: String?,
        page: Int?,
        pageSize: Int?,
    ): Response<ReportHistoryResponse> {
        val query = ReportsHistoryQuery(date = date, month = month, page = page, pageSize = pageSize)
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

    override suspend fun deactivate(id: Int): Response<SuccessResponse> {
        deactivatedIds += id
        return deactivateResponse
    }

    override suspend fun delete(id: Int): Response<Unit> {
        deletedIds += id
        // A real server removes the row, so the reload that follows a deletion has to see a smaller
        // history; a fake that only recorded the id would hand the reload the same page and hide the
        // step-back from the test that exists to prove it.
        if (deleteResponse.isSuccessful) remainingHistoryItems -= 1
        return deleteResponse
    }

    /**
     * One page of a history the lifecycle calls shrink, at the server's own page size.
     *
     * The arithmetic is the server's: pages of [PAGE_SIZE], `totalPages` rounded up, and a page past the
     * end answering an empty list with a well-formed pagination rather than an error.
     */
    private fun liveHistory(page: Int): ReportHistoryResponse {
        val total = remainingHistoryItems.coerceAtLeast(0)
        val start = (page - 1) * PAGE_SIZE
        val onPage = (total - start).coerceIn(0, PAGE_SIZE)

        return ReportHistoryResponse(
            items = (0 until onPage).map { historyItem(id = start + it + 1) },
            pagination = ScheduleHistoryResponsePagination(
                page = page,
                pageSize = PAGE_SIZE,
                total = total,
                totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE,
            ),
        )
    }

    override suspend fun photos(id: Int): Response<PhotosResponse> = Response.success(PhotosResponse(photos = photosToReturn))

    override suspend fun updateReport(id: Int, body: UpdateReportRequest): Response<ReportResponse> = error(NOT_USED)

    override suspend fun export(id: Int): Response<TextResponse> =
        if (reportToReturn == null) Response.error(404, EMPTY_BODY) else Response.success(TextResponse(text = exportToReturn))

    override suspend fun attachPhoto(id: Int, file: MultipartBody.Part): Response<PhotoResponse> {
        attachCalls++
        return attachResponse
    }

    override suspend fun deletePhoto(photoId: Int): Response<SuccessResponse> =
        Response.success(SuccessResponse(success = SuccessResponse.Success.entries.first()))

    companion object {
        const val NOT_USED = "this fake does not implement that call; add it when a test needs it"
        const val DEFAULT_TURNO = "tarde"

        /** The server's own default, which is what a page of the live history holds. */
        const val PAGE_SIZE = 30

        val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())

        /** Ids the tests refer to, named so an assertion reads as the task it is about. */
        const val CHECK_TASK_ID = 1
        const val PRODUCT_TASK_ID = 2
        const val TEMPERATURE_TASK_ID = 3

        /** The temperature task's own name, which is what the payload must carry as `location`. */
        const val TEMPERATURE_TASK_NAME = "Câmara Fria"

        /**
         * One task of each element type.
         *
         * The temperature task declares two readings, so a test can check that the form opens with as
         * many slots as the task owes rather than one.
         */
        fun defaultCategories() = listOf(
            CategoriesResponseCategoriesInner(
                id = 1,
                parentCategoryId = null,
                namePt = "Higiene",
                nameEs = "Higiene",
                sortOrder = 1,
                categoryType = CategoriesResponseCategoriesInner.CategoryType.entries.first(),
                tasks = listOf(
                    task(CHECK_TASK_ID, "Limpeza geral", ReportCategoryTasksInner.TaskType.check),
                    task(PRODUCT_TASK_ID, "Assaí do dia", ReportCategoryTasksInner.TaskType.check_assai),
                    task(TEMPERATURE_TASK_ID, TEMPERATURE_TASK_NAME, ReportCategoryTasksInner.TaskType.temperature, readings = 2),
                ),
            ),
        )

        private fun task(
            id: Int,
            name: String,
            type: ReportCategoryTasksInner.TaskType,
            readings: Int = 1,
        ) = ReportCategoryTasksInner(
            id = id,
            categoryId = 1,
            namePt = name,
            nameEs = name,
            taskType = type,
            temperatureReadings = readings,
        )

        fun defaultOffers() = ProductsResponse(
            assai = listOf(ProductsResponse.Assai.entries.first()),
            normal = listOf(ProductsResponse.Normal.entries.first()),
        )

        /** One item and a one-page pagination, which is the smallest complete answer. */
        fun defaultHistory() = ReportHistoryResponse(
            items = listOf(historyItem()),
            pagination = ScheduleHistoryResponsePagination(page = 1, pageSize = 30, total = 1, totalPages = 1),
        )

        /**
         * One history item, with all five flags the server's projection always emits.
         *
         * `readOnly` is written as `!canEdit` rather than passed in, because that is what the server
         * computes; a fake that could send a contradiction would let a test pass against a combination
         * the backend cannot produce.
         */
        fun historyItem(
            id: Int = 1,
            reportDate: String = "2026-09-18",
            isActive: Boolean = true,
            canEdit: Boolean = true,
        ) = ReportsResponseReportsInner(
            id = id,
            turno = ReportsResponseReportsInner.Turno.entries.first { it.value == DEFAULT_TURNO },
            reportDate = reportDate,
            notes = null,
            createdAt = "2026-09-18T10:00:00Z",
            user = ReportListItemUser(id = 1, displayName = "admin"),
            isActive = isActive,
            readOnly = !canEdit,
            canEdit = canEdit,
            canDeactivate = true,
            canDelete = true,
        )

        fun createdReport(id: Int = 42) = ReportResponseReport(
            id = id,
            turno = ReportResponseReport.Turno.entries.first { it.value == DEFAULT_TURNO },
            reportDate = "2026-09-18",
            notes = null,
            createdAt = "2026-09-18T10:00:00Z",
            user = ReportListItemUser(id = 1, displayName = "admin"),
            updatedAt = "2026-09-18T10:00:00Z",
            items = emptyList<ReportDetailItemsInner>(),
            temperatures = emptyList<ReportDetailTemperaturesInner>(),
        )

        /** One attached photo, with the fields both the screen and the server carry. */
        fun aPhoto(id: Int = 7) = PhotosResponsePhotosInner(
            id = id,
            reportId = 42,
            filePath = "photos/photo_$id.jpg",
            fileSize = 1024,
            mimeType = "image/jpeg",
            publicToken = "tok$id",
            createdAt = "2026-09-18T10:05:00Z",
            url = "https://example.test/api/reports/photos/$id",
            publicUrl = "https://example.test/p/tok$id",
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
data class ReportsHistoryQuery(
    val date: String?,
    val month: String?,
    val page: Int?,
    val pageSize: Int?,
)
