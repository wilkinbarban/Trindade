package com.trindade.app.aero

import com.trindade.app.network.AeroAuditLogRequest
import com.trindade.app.network.AeroMovementsSaved
import com.trindade.app.network.AeroMovementsSnapshotRequest
import com.trindade.app.network.AeroStoredMovement
import com.trindade.app.network.MassasApi
import com.trindade.app.network.runCatchingCancellable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The two ends of a dispatch, wired to the clients that actually speak.
 *
 * Thin on purpose: every decision lives in [AeroDispatcher] and every byte on the wire lives in the clients.
 * This file only exists so the sequence can be tested without Android, and so Hilt has something to build.
 */
@Singleton
class AeroErpAdapter @Inject constructor(
    private val client: AeroErpClient,
) : AeroErpPort {
    override suspend fun postEntry(settings: AeroSettings, payload: AeroEntryPayload): AeroWriteResult {
        val url = settings.normalizedWsUrl ?: return AeroWriteResult.Failed(null)
        return client.postEntry(url, settings.username, settings.password, payload)
    }

    override suspend fun readMovements(settings: AeroSettings, date: String): AeroMovementsResult {
        val url = settings.normalizedWsUrl ?: return AeroMovementsResult.Failed(null)
        return client.readMovements(url, settings.username, settings.password, date)
    }
}

/**
 * The backend's copy of the plant day, plus the shared audit log.
 *
 * Both calls are best-effort by design: a snapshot that fails to store leaves the previous one standing, and a
 * log line that fails to travel must never turn a launch into an error the operator will retry.
 */
@Singleton
class AeroMirrorAdapter @Inject constructor(
    private val api: MassasApi,
) : AeroMirrorPort {

    override suspend fun saveMovements(date: String, movements: List<AeroErpMovement>): AeroMovementsSaved? {
        val body = AeroMovementsSnapshotRequest(
            date = date,
            movements = movements.map { it.toStored() },
        )
        return runCatchingCancellable { api.saveMovements(body) }
            .getOrNull()
            ?.takeIf { it.isSuccessful }
            ?.body()
    }

    override suspend fun log(request: AeroAuditLogRequest) {
        runCatchingCancellable { api.logEvent(request) }
    }

    fun AeroErpMovement.toStored() = AeroStoredMovement(
        entLan = entLan,
        productCode = productCode,
        productDesc = productDesc,
        quantity = quantity,
        entryDate = entryDate,
        operatorName = operatorName,
        movementType = movementType,
    )
}

/**
 * The stored snapshot read back as the domain's day.
 *
 * The backend keeps the ERP's own column names; the screen reasons in the names the web already uses. The
 * mapping lives here so neither side has to know the other's spelling.
 */
fun AeroStoredMovement.toLogic(): AeroErpMovement = AeroErpMovement(
    entLan = entLan,
    productCode = productCode,
    productDesc = productDesc,
    quantity = quantity,
    entryDate = entryDate,
    operatorName = operatorName,
    movementType = movementType,
    ownerUserId = ownerUserId,
)
