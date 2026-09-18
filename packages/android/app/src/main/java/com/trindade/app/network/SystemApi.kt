package com.trindade.app.network

import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.contract.models.HealthResponse
import com.trindade.app.contract.models.UserOptionsResponse
import retrofit2.Response
import retrofit2.http.GET

/**
 * The routes that belong to no module: the dashboard summary, the user list the history filters use,
 * and the liveness probe.
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
}
