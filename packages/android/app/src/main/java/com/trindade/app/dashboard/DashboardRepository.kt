package com.trindade.app.dashboard

import com.trindade.app.contract.models.DashboardSummary
import com.trindade.app.network.SystemApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The dashboard's one read.
 *
 * Null means the server could not be asked, and the screen has to tell that apart from a summary of
 * zeroes: every count in this answer is an `Int`, so a failed read that answered with defaults would
 * be a screen reporting an empty day, which is the one thing a summary must never invent.
 */
@Singleton
class DashboardRepository @Inject constructor(
    private val api: SystemApi,
) {

    suspend fun summary(): DashboardSummary? =
        runCatchingCancellable { api.dashboardSummary() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
}
