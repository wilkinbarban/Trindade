package com.trindade.app.massas

import com.trindade.app.network.CreateMassasLaunchItem
import com.trindade.app.network.CreateMassasLaunchRequest
import com.trindade.app.network.MassaHistoryDay
import com.trindade.app.network.MassaIndividualLaunch
import com.trindade.app.network.MassasApi
import com.trindade.app.network.AeroMovementsStoredDay
import com.trindade.app.contract.models.MassasReportDayView
import com.trindade.app.network.MassasLaunchDayView
import com.trindade.app.network.UpdateMassasLaunchRequest
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton
import retrofit2.Response

sealed interface MassasWriteResult {
    /**
     * `created` carries the rows this write produced, when the backend answers with them.
     *
     * The ERP dispatch needs their ids for `ENT_OBS`, and looking them up by product and operator afterwards
     * would pick the wrong row when the same operator launches the same product twice in one day.
     */
    data class Saved(
        val view: MassasLaunchDayView,
        val created: List<MassaIndividualLaunch> = emptyList(),
    ) : MassasWriteResult
    data class Refused(val statusCode: Int) : MassasWriteResult
    data object Unreachable : MassasWriteResult
}

/**
 * The one read the report screens need, as its own contract.
 *
 * A narrow interface rather than the whole repository, so a report view model can be built in a test without a
 * second API fake, and so the card's absence of a source is expressible: no source, no card.
 */
interface MassasDaySource {
    suspend fun reportDay(date: String): com.trindade.app.contract.models.MassasReportDayView?
}

@Singleton
open class MassasRepository @Inject constructor(
    private val api: MassasApi,
) : MassasDaySource {
    open suspend fun day(date: String): MassasLaunchDayView? =
        runCatchingCancellable { api.day(date) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    /** The day the report card draws: one read, no reconciliation work on the phone. */
    override suspend fun reportDay(date: String): MassasReportDayView? =
        runCatchingCancellable { api.reportDay(date) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    open suspend fun history(): List<MassaHistoryDay>? =
        runCatchingCancellable { api.history() }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
            ?.days

    /**
     * The plant's day as the backend stored it.
     *
     * Read from Trindade rather than from the ERP: this call must work with no factory Wi-Fi, which is the
     * whole reason the snapshot is kept server-side.
     */
    open suspend fun storedAeroMovements(date: String): AeroMovementsStoredDay? =
        runCatchingCancellable { api.storedMovements(date) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()

    open suspend fun createLaunch(
        date: String,
        items: List<CreateMassasLaunchItem>,
        entLan: Int? = null,
        operatorName: String? = null,
    ): MassasWriteResult {
        val response = runCatchingCancellable {
            api.createLaunch(
                CreateMassasLaunchRequest(
                    date = date,
                    entLan = entLan,
                    operatorName = operatorName,
                    items = items,
                ),
            )
        }.getOrNull() ?: return MassasWriteResult.Unreachable

        if (response.isSuccessful) {
            val created = response.body()?.created.orEmpty()
            val refreshedDay = day(date)
            return MassasWriteResult.Saved(
                view = refreshedDay ?: MassasLaunchDayView(
                    date = date,
                    products = emptyList(),
                    launches = emptyList(),
                ),
                created = created,
            )
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
