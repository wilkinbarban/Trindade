package com.trindade.app.aero

import com.trindade.app.network.AeroMovementsSaved

/**
 * The two trips to the plant, in the order they have to happen.
 *
 * A launch is stored in Trindade first and only then sent to the ERP, and the day's mirror is read from the ERP
 * before being stored back. Both orders look obvious written down and neither is obvious in a view model that
 * also edits rows, deletes launches and polls. So the sequence lives here, over ports, where a test can see the
 * calls in the order they were made.
 *
 * Nothing in this file touches Android, OkHttp or the database: [AeroErpPort] and [AeroMirrorPort] are the two
 * sides, and the real implementations are the thin adapters that wrap the clients.
 */
interface AeroErpPort {
    suspend fun postEntry(settings: AeroSettings, payload: AeroEntryPayload): AeroWriteResult

    suspend fun readMovements(settings: AeroSettings, date: String): AeroMovementsResult
}

interface AeroMirrorPort {
    /** Stores the whole day's snapshot on the backend and returns its two lens counts. */
    suspend fun saveMovements(date: String, movements: List<AeroErpMovement>): AeroMovementsSaved?

    /** Reports one integration event to the shared audit log. Never throws: a lost log line is not a lost launch. */
    suspend fun log(request: com.trindade.app.network.AeroAuditLogRequest)
}

/**
 * One launch on its way to the ERP.
 *
 * Carried as data rather than as the launch row, because what the ERP records is units and the row's boxes
 * are only how the operator counted them. Naming the launch id keeps `ENT_OBS` traceable.
 */
data class AeroDispatchRequest(
    val launchId: Int,
    val productCode: String,
    val productName: String,
    val units: Double,
    val date: String,
)

/** What a day's synchronization produced. */
sealed interface AeroSyncOutcome {
    data class Synced(val saved: AeroMovementsSaved) : AeroSyncOutcome

    /** The plant did not answer, so the previous snapshot stands. */
    data object Unreachable : AeroSyncOutcome

    /** The ERP answered with a refusal, or the backend refused to store the snapshot. */
    data class Failed(val reason: String?) : AeroSyncOutcome
}

class AeroDispatcher(
    private val erp: AeroErpPort,
    private val mirror: AeroMirrorPort,
) {

    /**
     * Sends one stored launch to the ERP.
     *
     * The launch already exists locally: this call is the second half, and the only thing at stake is whether
     * the row ends up reconciled or pending. A failure never undoes the local record, which is why this returns
     * an outcome instead of throwing.
     */
    suspend fun dispatch(
        settings: AeroSettings,
        request: AeroDispatchRequest,
    ): AeroDispatchOutcome {
        val normalized = settings.normalizedWsUrl
        if (normalized == null || !settings.ready) {
            // Nothing is sent and nothing is logged against the ERP: the log line would name a destination
            // that was never called, and the operator has to configure the integration first.
            return AeroDispatchOutcome.Refused("not-configured")
        }

        val payload = buildEntradaItemPayload(
            productCode = request.productCode,
            productName = request.productName,
            quantity = request.units,
            entryDate = request.date,
            userCode = settings.userCode,
            launchIds = listOf(request.launchId),
        )
        val startedAt = System.currentTimeMillis()
        val result = erp.postEntry(settings, payload)
        val elapsed = System.currentTimeMillis() - startedAt

        return when (result) {
            is AeroWriteResult.Ok -> {
                mirror.log(
                    AeroAudit.request(
                        event = AeroAudit.Event.DISPATCH,
                        destinationUrl = aeroEndpoint(normalized, "entradasitem"),
                        phase = "response",
                        httpStatus = 200,
                        durationMs = elapsed,
                        detail = "launch #${request.launchId}",
                    ),
                )
                AeroDispatchOutcome.Sent
            }

            // A refusal with no sentence is a transport failure: the ERP never answered, so nothing was
            // recorded on its side and the row can be resent later.
            is AeroWriteResult.Failed -> {
                val unreachable = result.message == null
                mirror.log(
                    AeroAudit.request(
                        event = AeroAudit.Event.DISPATCH,
                        destinationUrl = aeroEndpoint(normalized, "entradasitem"),
                        phase = "response",
                        httpStatus = if (unreachable) null else 200,
                        durationMs = elapsed,
                        detail = "launch #${request.launchId} · ${if (unreachable) "sem resposta" else result.message}",
                    ),
                )
                if (unreachable) AeroDispatchOutcome.Unreachable else AeroDispatchOutcome.Refused(result.message)
            }
        }
    }

    /**
     * Reads the plant's day and stores it on the backend.
     *
     * The snapshot is written only when the ERP answered: a failed read must leave yesterday's stored day
     * untouched, because a mirror that empties itself on a network blip reads as "the plant produced nothing".
     */
    suspend fun syncDay(settings: AeroSettings, date: String): AeroSyncOutcome {
        val normalized = settings.normalizedWsUrl
        if (normalized == null || !settings.ready) return AeroSyncOutcome.Failed("not-configured")

        val startedAt = System.currentTimeMillis()
        val read = erp.readMovements(settings, date)
        val elapsed = System.currentTimeMillis() - startedAt
        val destination = aeroMovementsUrl(normalized)

        return when (read) {
            is AeroMovementsResult.Ok -> {
                val saved = mirror.saveMovements(date, read.movements)
                mirror.log(
                    AeroAudit.request(
                        event = AeroAudit.Event.SYNC,
                        destinationUrl = destination,
                        phase = "response",
                        httpStatus = if (saved == null) null else 200,
                        durationMs = elapsed,
                        detail = "date=$date · seats=${read.movements.size} · saved=${saved?.saved ?: 0}",
                    ),
                )
                if (saved == null) AeroSyncOutcome.Failed("snapshot-refused") else AeroSyncOutcome.Synced(saved)
            }

            is AeroMovementsResult.Failed -> {
                mirror.log(
                    AeroAudit.request(
                        event = AeroAudit.Event.SYNC,
                        destinationUrl = destination,
                        phase = "response",
                        httpStatus = null,
                        durationMs = elapsed,
                        detail = "date=$date · ${read.message ?: "sem resposta"}",
                    ),
                )
                if (read.message == null) AeroSyncOutcome.Unreachable else AeroSyncOutcome.Failed(read.message)
            }
        }
    }
}
