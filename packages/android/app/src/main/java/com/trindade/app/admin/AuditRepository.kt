package com.trindade.app.admin

import com.trindade.app.contract.models.AuditResponse
import com.trindade.app.network.AdminApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Result hierarchy for audit log queries.
 */
sealed interface AuditQueryResult {
    data class Success(val page: AuditResponse) : AuditQueryResult
    data class Refused(val statusCode: Int) : AuditQueryResult
    data object Unreachable : AuditQueryResult
}

/**
 * Data layer for system audit logs.
 *
 * Used by [AuditViewModel] to query paginated, filtered audit logs.
 * Read-only interface gated server-side by `adminGuard` (Administrador only).
 */
@Singleton
class AuditRepository @Inject constructor(
    private val api: AdminApi,
) {
    /**
     * Queries paginated audit logs with optional filters for action, entityType, and userId.
     *
     * Answers:
     * - [AuditQueryResult.Success] containing the paginated log records and pagination metadata.
     * - [AuditQueryResult.Refused] with HTTP status code (e.g. 403 when caller is not an administrator, 400 on invalid query).
     * - [AuditQueryResult.Unreachable] on network or connectivity failure.
     */
    suspend fun queryAuditLogs(
        page: Int? = null,
        limit: Int? = null,
        action: String? = null,
        entityType: String? = null,
        userId: Int? = null,
    ): AuditQueryResult {
        val response = runCatchingCancellable {
            api.audit(
                page = page,
                limit = limit,
                action = action?.takeIf { it.isNotBlank() },
                entityType = entityType?.takeIf { it.isNotBlank() },
                userId = userId,
            )
        }.getOrNull() ?: return AuditQueryResult.Unreachable

        val body = response.body()
        return if (response.isSuccessful && body != null) {
            AuditQueryResult.Success(body)
        } else {
            AuditQueryResult.Refused(response.code())
        }
    }
}
