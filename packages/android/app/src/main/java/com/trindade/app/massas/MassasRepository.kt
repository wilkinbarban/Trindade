package com.trindade.app.massas

import com.trindade.app.network.CreateMassasLaunchItem
import com.trindade.app.network.CreateMassasLaunchRequest
import com.trindade.app.network.MassaHistoryDay
import com.trindade.app.network.MassasApi
import com.trindade.app.network.MassasLaunchDayView
import com.trindade.app.network.UpdateMassasLaunchRequest
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

sealed interface MassasWriteResult {
    data class Saved(val view: MassasLaunchDayView) : MassasWriteResult
    data class Refused(val statusCode: Int) : MassasWriteResult
    data object Unreachable : MassasWriteResult
}

@Singleton
class MassasRepository @Inject constructor(
    private val api: MassasApi,
) {
    suspend fun day(date: String): MassasLaunchDayView? =
        runCatchingCancellable { api.day(date) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    suspend fun history(): List<MassaHistoryDay>? =
        runCatchingCancellable { api.history() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.days

    suspend fun createLaunch(date: String, items: List<CreateMassasLaunchItem>): MassasWriteResult =
        write { api.createLaunch(CreateMassasLaunchRequest(date = date, items = items)) }

    suspend fun updateLaunch(id: Int, boxes: Int): MassasWriteResult =
        write { api.updateLaunch(id, UpdateMassasLaunchRequest(boxes = boxes)) }

    suspend fun deleteLaunch(id: Int): MassasWriteResult =
        write { api.deleteLaunch(id) }

    private suspend fun write(call: suspend () -> Response<MassasLaunchDayView>): MassasWriteResult {
        val response = runCatchingCancellable { call() }.getOrNull() ?: return MassasWriteResult.Unreachable
        val body = response.body()
        return if (response.isSuccessful && body != null) {
            MassasWriteResult.Saved(body)
        } else {
            MassasWriteResult.Refused(response.code())
        }
    }
}
