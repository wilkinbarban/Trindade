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
open class MassasRepository @Inject constructor(
    private val api: MassasApi,
) {
    open suspend fun day(date: String): MassasLaunchDayView? =
        runCatchingCancellable { api.day(date) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    open suspend fun history(): List<MassaHistoryDay>? =
        runCatchingCancellable { api.history() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.days

    open suspend fun createLaunch(date: String, items: List<CreateMassasLaunchItem>): MassasWriteResult {
        val response = runCatchingCancellable {
            api.createLaunch(CreateMassasLaunchRequest(date = date, items = items))
        }.getOrNull() ?: return MassasWriteResult.Unreachable

        if (response.isSuccessful) {
            val refreshedDay = day(date)
            return if (refreshedDay != null) {
                MassasWriteResult.Saved(refreshedDay)
            } else {
                MassasWriteResult.Saved(
                    MassasLaunchDayView(
                        date = date,
                        products = emptyList(),
                        launches = emptyList(),
                    ),
                )
            }
        }
        return MassasWriteResult.Refused(response.code())
    }

    open suspend fun updateLaunch(id: Int, boxes: Int, date: String? = null): MassasWriteResult {
        val response = runCatchingCancellable {
            api.updateLaunch(id, UpdateMassasLaunchRequest(boxes = boxes))
        }.getOrNull() ?: return MassasWriteResult.Unreachable

        if (response.isSuccessful) {
            val targetDate = date ?: response.body()?.launchDate ?: response.body()?.date
            val refreshedDay = if (!targetDate.isNullOrEmpty()) day(targetDate) else null
            return if (refreshedDay != null) {
                MassasWriteResult.Saved(refreshedDay)
            } else {
                MassasWriteResult.Saved(
                    MassasLaunchDayView(
                        date = targetDate ?: "",
                        products = emptyList(),
                        launches = emptyList(),
                    ),
                )
            }
        }
        return MassasWriteResult.Refused(response.code())
    }

    open suspend fun deleteLaunch(id: Int, date: String? = null): MassasWriteResult {
        val response = runCatchingCancellable {
            api.deleteLaunch(id)
        }.getOrNull() ?: return MassasWriteResult.Unreachable

        if (response.isSuccessful) {
            val refreshedDay = if (!date.isNullOrEmpty()) day(date) else null
            return if (refreshedDay != null) {
                MassasWriteResult.Saved(refreshedDay)
            } else {
                MassasWriteResult.Saved(
                    MassasLaunchDayView(
                        date = date ?: "",
                        products = emptyList(),
                        launches = emptyList(),
                    ),
                )
            }
        }
        return MassasWriteResult.Refused(response.code())
    }
}
