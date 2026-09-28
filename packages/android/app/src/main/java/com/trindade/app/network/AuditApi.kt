package com.trindade.app.network

import com.trindade.app.contract.models.AuditResponse
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Dedicated audit log network interface.
 *
 * Dedicated to avoid forcing every existing AdminApi fake implementation in tests to change.
 * Calls the backend GET api/admin/audit endpoint which is restricted to Administrador.
 */
interface AuditApi {
    @GET("api/admin/audit")
    suspend fun audit(
        @Query("page") page: Int? = null,
        @Query("limit") limit: Int? = null,
        @Query("action") action: String? = null,
        @Query("entityType") entityType: String? = null,
        @Query("userId") userId: Int? = null,
    ): Response<AuditResponse>
}
