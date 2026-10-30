package com.trindade.app.massas

import com.trindade.app.aero.AeroEntryPayload
import com.trindade.app.aero.AeroErpMovement
import com.trindade.app.aero.AeroErpPort
import com.trindade.app.aero.AeroMirrorPort
import com.trindade.app.aero.AeroMovementsResult
import com.trindade.app.aero.AeroSettings
import com.trindade.app.aero.AeroSettingsPort
import com.trindade.app.aero.AeroWriteResult
import com.trindade.app.aero.TabletAckPort
import com.trindade.app.network.AeroAuditLogRequest
import com.trindade.app.network.AeroMovementsSaved

/**
 * The device-owned state of a launch screen, in memory.
 *
 * The two stores behind these ports need a Context and the Keystore; the view model's decisions do not, and
 * testing them should not require either. The real classes stay the only shipping implementations.
 */
class FakeSettingsPort(
    var settings: AeroSettings = AeroSettings(),
) : AeroSettingsPort {
    val syncedAt = mutableMapOf<String, String>()
    var savedCount = 0

    override fun load(): AeroSettings = settings

    override fun save(settings: AeroSettings) {
        this.settings = settings
        savedCount++
    }

    override fun isConfigured(): Boolean = settings.ready

    override fun syncedAt(date: String): String? = syncedAt[date]

    override fun markSynced(date: String, at: String) {
        syncedAt[date] = at
    }
}

class FakeAckPort(initial: Set<Int> = emptySet()) : TabletAckPort {
    val acked = initial.toMutableSet()

    override fun ackedIds(): Set<Int> = acked.toSet()

    override fun ack(launchId: Int) {
        acked += launchId
    }
}

/**
 * The plant, answering whatever the test asks and recording what it was asked.
 *
 * `postEntry` records the payloads so a test can assert units rather than the boxes the table showed, which
 * is the whole reason the dispatcher converts before it writes.
 */
class FakeErpPort : AeroErpPort {
    var writeResult: AeroWriteResult = AeroWriteResult.Ok(null)
    var movementsResult: AeroMovementsResult = AeroMovementsResult.Ok(emptyList(), skipped = 0)
    val payloads = mutableListOf<AeroEntryPayload>()
    var readDates = mutableListOf<String>()

    override suspend fun postEntry(settings: AeroSettings, payload: AeroEntryPayload): AeroWriteResult {
        payloads += payload
        return writeResult
    }

    override suspend fun readMovements(settings: AeroSettings, date: String): AeroMovementsResult {
        readDates += date
        return movementsResult
    }
}

/** The backend's side of the same conversation: the shared log and the stored snapshot. */
class FakeMirrorPort : AeroMirrorPort {
    val logs = mutableListOf<AeroAuditLogRequest>()
    val snapshots = mutableListOf<Pair<String, List<AeroErpMovement>>>()
    var saved: AeroMovementsSaved? = AeroMovementsSaved(saved = 0, massas = 0, insumos = 0)
    var failSnapshot = false

    override suspend fun saveMovements(
        date: String,
        movements: List<AeroErpMovement>,
    ): AeroMovementsSaved? {
        snapshots += date to movements
        return if (failSnapshot) null else saved
    }

    override suspend fun log(request: AeroAuditLogRequest) {
        logs += request
    }
}
