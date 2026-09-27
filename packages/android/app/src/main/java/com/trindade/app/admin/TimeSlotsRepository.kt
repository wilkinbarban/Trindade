package com.trindade.app.admin

import com.trindade.app.contract.models.TimeSlotsResponse
import com.trindade.app.contract.models.UpdateTimeSlotsRequest
import com.trindade.app.network.AdminApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

/** A failed read is null, not an empty list; write refusals keep their HTTP status. */
@Singleton
class TimeSlotsRepository @Inject constructor(private val api: AdminApi) {
    suspend fun timeSlots(): List<String>? =
        runCatchingCancellable { api.timeSlots() }.getOrNull()
            ?.takeIf { it.isSuccessful }?.body()?.timeSlots

    suspend fun update(body: UpdateTimeSlotsRequest): TimeSlotWriteResult =
        write { api.updateTimeSlots(body) }

    suspend fun update(timeSlots: List<String>): TimeSlotWriteResult =
        update(UpdateTimeSlotsRequest(timeSlots = timeSlots))

    suspend fun updateTimeSlots(body: UpdateTimeSlotsRequest): TimeSlotWriteResult = update(body)

    suspend fun updateTimeSlots(timeSlots: List<String>): TimeSlotWriteResult = update(timeSlots)

    private suspend fun write(call: suspend () -> Response<TimeSlotsResponse>): TimeSlotWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull()
            ?: return TimeSlotWriteResult.Unreachable
        if (!response.isSuccessful) return TimeSlotWriteResult.Refused(response.code())
        return response.body()?.timeSlots?.let { TimeSlotWriteResult.Saved(it) } ?: TimeSlotWriteResult.Unreachable
    }
}

sealed interface TimeSlotWriteResult {
    data class Saved(val timeSlots: List<String>? = null) : TimeSlotWriteResult
    data class Refused(val status: Int) : TimeSlotWriteResult
    data object Unreachable : TimeSlotWriteResult
}

typealias TimeSlotsWriteResult = TimeSlotWriteResult
