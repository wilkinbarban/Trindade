package com.trindade.app.admin

import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The data layer for administrator-only loading time slot management.
 *
 * Used by [TimeSlotsAdminViewModel] to read and replace configured time slots.
 *
 * Reads answer null when unreachable or unsuccessful.
 * Writes answer an explicit result hierarchy so callers distinguish:
 * - A successful write carrying the stored time slots
 * - A server refusal with its HTTP status code (400 on empty array or validation error, 403 on role guard, etc.)
 * - An unreachable/network error to offer retry
 *
 * Uses PUT /api/admin/time-slots to atomically replace the configured slots on the server.
 * Note: does not use the read-only /api/loading/time-slots endpoint.
 */
@Singleton
class TimeSlotsAdminRepository @Inject constructor(
    private val api: AdminApi,
) {

    /**
     * Reads the configured loading time slots. Gated server-side by adminGuard (Administrador only).
     */
    suspend fun timeSlots(): List<String>? =
        runCatchingCancellable { api.timeSlots() }.getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.timeSlots

    /**
     * Replaces the configured loading time slots. Gated server-side by adminGuard (Administrador only).
     * Server PUT replaces the whole set atomically; at least one slot is required.
     */
    suspend fun updateTimeSlots(timeSlots: List<String>): TimeSlotsWriteResult {
        val request = UpdateTimeSlotsRequest(timeSlots = timeSlots)
        val response = runCatchingCancellable { api.updateTimeSlots(request) }.getOrNull()
            ?: return TimeSlotsWriteResult.Unreachable
        val body = response.body()
        return if (response.isSuccessful && body != null) {
            TimeSlotsWriteResult.Saved(body.timeSlots)
        } else {
            TimeSlotsWriteResult.Refused(response.code())
        }
    }
}

sealed interface TimeSlotsWriteResult {
    data class Saved(val timeSlots: List<String>) : TimeSlotsWriteResult
    data class Refused(val statusCode: Int) : TimeSlotsWriteResult
    data object Unreachable : TimeSlotsWriteResult
}
