package com.trindade.app.admin

import com.trindade.app.contract.models.AuditResponse
import com.trindade.app.network.AuditApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for administrator audit log queries.
 *
 * Read-only and fail-closed: invalid queries, non-2xx responses or network failures return null
 * rather than throwing or exposing an empty envelope over an error.
 */
@Singleton
class AuditRepository @Inject constructor(
    private val api: AuditApi,
) {
    /**
     * Reads one page of audit logs.
     *
     * @param page positive 1-based page number; non-positive values fail closed returning null
     * @param action optional action filter; blank strings are omitted
     * @param entityType optional entityType filter; blank strings are omitted
     * @param userId optional positive user ID filter; non-positive values fail closed returning null
     * @return [AuditResponse] if the query succeeded, or null if refused / unreachable / invalid
     */
    suspend fun audit(
        page: Int = 1,
        action: String? = null,
        entityType: String? = null,
        userId: Int? = null,
    ): AuditResponse? {
        if (page < 1) return null
        if (userId != null && userId <= 0) return null

        val cleanAction = action?.trim()?.takeIf { it.isNotEmpty() }
        val cleanEntityType = entityType?.trim()?.takeIf { it.isNotEmpty() }

        return runCatchingCancellable {
            api.audit(
                page = page,
                limit = FIXED_LIMIT,
                action = cleanAction,
                entityType = cleanEntityType,
                userId = userId,
            )
        }.getOrNull()?.takeIf { it.isSuccessful }?.body()
    }

    companion object {
        const val FIXED_LIMIT = 20
    }
}
