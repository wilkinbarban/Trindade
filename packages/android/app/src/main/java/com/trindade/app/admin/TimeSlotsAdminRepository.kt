package com.trindade.app.admin

import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimeSlotsAdminRepository @Inject constructor(
    private val api: AdminApi,
) {
    suspend fun fetchTimeSlots(): TimeSlotsReadResult {
        val response = runCatchingCancellable { api.timeSlots() }.getOrNull()
            ?: return TimeSlotsReadResult.Unreachable
        val body = response.body()
        return if (response.isSuccessful && body != null) {
            TimeSlotsReadResult.Success(body.timeSlots)
        } else {
            TimeSlotsReadResult.Refused(response.code())
        }
    }

    suspend fun timeSlots(): List<String>? =
        (fetchTimeSlots() as? TimeSlotsReadResult.Success)?.timeSlots

    suspend fun updateTimeSlots(timeSlots: List<String>): TimeSlotsAdminWriteResult {
        val request = UpdateTimeSlotsRequest(timeSlots = timeSlots)
        val response = runCatchingCancellable { api.updateTimeSlots(request) }.getOrNull()
            ?: return TimeSlotsAdminWriteResult.Unreachable
        val body = response.body()
        return if (response.isSuccessful && body != null) {
            TimeSlotsAdminWriteResult.Saved(body.timeSlots)
        } else {
            TimeSlotsAdminWriteResult.Refused(response.code())
        }
    }
}

sealed interface TimeSlotsReadResult {
    data class Success(val timeSlots: List<String>) : TimeSlotsReadResult
    data class Refused(val statusCode: Int) : TimeSlotsReadResult
    data object Unreachable : TimeSlotsReadResult
}

sealed interface TimeSlotsAdminWriteResult {
    data class Saved(val timeSlots: List<String>) : TimeSlotsAdminWriteResult
    data class Refused(val statusCode: Int) : TimeSlotsAdminWriteResult
    data object Unreachable : TimeSlotsAdminWriteResult
}
