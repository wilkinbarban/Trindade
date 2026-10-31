package com.trindade.app.network

import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.contract.models.HealthResponse
import com.trindade.app.contract.models.LatestReleaseResponse
import com.trindade.app.contract.models.UserOptionsResponse
import retrofit2.Response
import retrofit2.http.GET

/**
 * The routes that belong to no module: the dashboard summary, the user list the history filters use,
 * the release this installation serves, and the liveness probe.
 *
 * Grouped together because that is how the contract groups them -- they are registered outside any
 * module's prefix -- and splitting them into files named after nothing would be worse than one file
 * named after what they have in common.
 */
interface SystemApi {

    /**
     * The operational counts and progress pairs for the home screen. Every count is computed
     * server-side in São Paulo time, so the client never derives a date boundary of its own.
     */
    @GET("api/dashboard/summary")
    suspend fun dashboardSummary(): Response<DashboardSummary>

    /** The active users the history filters offer. */
    @GET("api/users/options")
    suspend fun userOptions(): Response<UserOptionsResponse>

    /**
     * Liveness only: it reports that the process is serving, not that its database is reachable. The
     * distinction is the point of having it, so do not read a success here as the API being usable.
     */
    @GET("api/health")
    suspend fun health(): Response<HealthResponse>

    /**
     * The build this installation serves, with the address its APK can be downloaded from.
     *
     * Here rather than at a release host of somebody else's, because the question the update check asks is
     * which binary *this* deployment publishes: two installations of this API can serve different builds on
     * the same day, and a third party cannot know which one the operator's phone talks to. A refusal or an
     * empty body is therefore "could not check" rather than "nothing newer", which is what
     * [com.trindade.app.update.UpdateRepository] returns as `null`.
     */
    @GET("api/app/latest-release")
    suspend fun latestRelease(): Response<LatestReleaseResponse>
}
