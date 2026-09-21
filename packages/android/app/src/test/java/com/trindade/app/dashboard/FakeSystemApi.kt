package com.trindade.app.dashboard

import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.contract.models.HealthResponse
import com.trindade.app.contract.models.UserOptionsResponse
import com.trindade.app.network.SystemApi
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Response

/**
 * A `SystemApi` that answers from memory, in the register of the loading fake: the interface is a plain
 * Kotlin interface, so the repository under test is the real one and only the network is replaced.
 *
 * The one read this slice makes has a *parameter* for its answer rather than a fixed one, because the
 * question this fake exists for is about the two answers a read can have: a summary, and nothing at all.
 * Passing null is the server refusing the read, which is the convention the loading fake's own nullable
 * reads use -- a fake that could only answer successfully could not reach the state the screen has to
 * tell apart from a summary of zeroes.
 *
 * The two methods no slice has needed yet throw rather than returning a plausible default, so a test
 * cannot pass while calling something it never meant to -- and that throw is also this fake's answer to
 * "was anything else asked?": the only way it could have happened is a call that fails the test.
 */
class FakeSystemApi(
    /** What the summary read answers. Null refuses it with a 500, the way the loading fake refuses a read. */
    private val summaryToReturn: Response<DashboardSummary>? = Response.success(summary()),
) : SystemApi {

    /** How many times the summary was asked for, so a caller can say a screen read it exactly once. */
    var summaryCalls = 0
        private set

    override suspend fun dashboardSummary(): Response<DashboardSummary> {
        summaryCalls += 1
        return summaryToReturn ?: Response.error(500, EMPTY_BODY)
    }

    override suspend fun userOptions(): Response<UserOptionsResponse> = error(NOT_USED)

    override suspend fun health(): Response<HealthResponse> = error(NOT_USED)

    companion object {
        const val NOT_USED = "this fake does not implement that call; add it when a test needs it"

        /**
         * The five counts a card draws, each one a number of its own.
         *
         * Distinct on purpose: two cards sharing a number would let an assertion pass because the screen
         * drew the other card's value, which is exactly the mistake a rendered-screen test exists to
         * catch -- and `reportsToday` is here as well even though no screen draws it, because the
         * summary the server sends carries it and a value left at zero would be indistinguishable from a
         * missing read.
         */
        const val REPORTS_TODAY = 2
        const val SCHEDULES_TOMORROW = 7
        const val ACTIVE_USERS = 5
        const val REPORTS_TOTAL = 42
        const val SCHEDULES_TOTAL = 13
        const val HIGIENE_DONE = 3
        const val HIGIENE_TOTAL = 6
        const val RECEPCION_DONE = 4
        const val RECEPCION_TOTAL = 8
        const val LATEST_REPORT_ID = 99

        /**
         * One summary, complete.
         *
         * `latestReportId` is the only argument, because it is the only field the dashboard's behaviour
         * turns on: the reports card opens the report it names when there is one and the reports list
         * when there is not, so the two states the server can be in are what a test has to choose
         * between.
         */
        fun summary(latestReportId: Int? = LATEST_REPORT_ID) = DashboardSummary(
            reportsToday = REPORTS_TODAY,
            schedulesTomorrow = SCHEDULES_TOMORROW,
            activeUsers = ACTIVE_USERS,
            reportsTotal = REPORTS_TOTAL,
            schedulesTotal = SCHEDULES_TOTAL,
            latestReportId = latestReportId,
            higieneDone = HIGIENE_DONE,
            higieneTotal = HIGIENE_TOTAL,
            recepcionDone = RECEPCION_DONE,
            recepcionTotal = RECEPCION_TOTAL,
        )

        /** The body a refusal carries. No caller reads it, and this fake does not read it either. */
        val EMPTY_BODY: okhttp3.ResponseBody = "{}".toResponseBody("application/json".toMediaType())
    }
}
