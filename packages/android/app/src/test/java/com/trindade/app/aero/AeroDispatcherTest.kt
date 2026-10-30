package com.trindade.app.aero

import com.trindade.app.network.AeroAuditLogRequest
import kotlinx.serialization.json.JsonPrimitive
import com.trindade.app.network.AeroMovementsSaved
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The order of the two trips to the plant.
 *
 * What is asserted here is not that a call happened but that it happened in this position: a dispatch that
 * reaches the ERP before the launch exists locally would leave a seat the app cannot reconcile, and a mirror
 * that stores an empty day after a failed read would make the plant look idle. The fakes record every call in
 * one list so the sequence is readable in the failure message.
 */
class AeroDispatcherTest {

    private val settings = AeroSettings(
        wsUrl = "http://192.168.25.250:1453/api",
        username = "wilkin",
        password = "s3cr3t",
        userCode = "7",
    )

    private val request = AeroDispatchRequest(
        launchId = 42,
        productCode = "1192",
        productName = "MASSA DE PASTEL 500G",
        units = 15.0,
        date = "2026-10-30",
    )

    private class Recorder {
        val calls = mutableListOf<String>()
        val logs = mutableListOf<AeroAuditLogRequest>()
        /** Every payload the app would have written, so `ENT_USU` can be read where the ERP reads it. */
        val payloads = mutableListOf<AeroEntryPayload>()
    }

    private class FakeErp(
        private val recorder: Recorder,
        private val entry: AeroWriteResult,
        private val movements: AeroMovementsResult,
    ) : AeroErpPort {
        override suspend fun postEntry(settings: AeroSettings, payload: AeroEntryPayload): AeroWriteResult {
            recorder.calls += "erp:entry:${payload.productCode}:${payload.quantity}:${payload.observation}"
            recorder.payloads += payload
            return entry
        }

        override suspend fun readMovements(settings: AeroSettings, date: String): AeroMovementsResult {
            recorder.calls += "erp:movements:$date"
            return movements
        }
    }

    private class FakeMirror(
        private val recorder: Recorder,
        private val saved: AeroMovementsSaved?,
    ) : AeroMirrorPort {
        override suspend fun saveMovements(date: String, movements: List<AeroErpMovement>): AeroMovementsSaved? {
            recorder.calls += "mirror:save:$date:${movements.size}"
            return saved
        }

        override suspend fun log(request: AeroAuditLogRequest) {
            recorder.calls += "mirror:log:${request.event}:${request.phase}"
            recorder.logs += request
        }
    }

    private fun dispatcher(
        recorder: Recorder,
        entry: AeroWriteResult = AeroWriteResult.Ok(null),
        movements: AeroMovementsResult = AeroMovementsResult.Ok(emptyList(), skipped = 0),
        saved: AeroMovementsSaved? = AeroMovementsSaved(saved = 0, massas = 0, insumos = 0),
    ) = AeroDispatcher(
        erp = FakeErp(recorder, entry, movements),
        mirror = FakeMirror(recorder, saved),
    )

    @Test
    fun `a dispatch sends the units with the launch named in the observation`() = runTest {
        val recorder = Recorder()

        val outcome = dispatcher(recorder).dispatch(settings, request)

        assertEquals(AeroDispatchOutcome.Sent, outcome)
        assertEquals("erp:entry:001192:1500:Trindade #42", recorder.calls.first())
        assertTrue(recorder.calls.contains("mirror:log:dispatch:response"))
    }

    @Test
    fun `the seat is signed with the launch's operator, not with the phone's configured user`() = runTest {
        val recorder = Recorder()

        // The phone belongs to Wilkin and the launch belongs to Jaime: the company records who typed the
        // boxes, in the uppercase the Delphi binary sends and the ERP accepts.
        dispatcher(recorder).dispatch(settings, request.copy(operatorName = "Jaime"))

        assertEquals(JsonPrimitive("JAIME"), recorder.payloads.single().userCode)
    }

    @Test
    fun `a launch with no operator keeps signing with the configured user`() = runTest {
        val recorder = Recorder()

        dispatcher(recorder).dispatch(settings, request.copy(operatorName = null))

        // `7` is the configured code, and it stays numeric: an old row must not invent a name.
        assertEquals(JsonPrimitive(7), recorder.payloads.single().userCode)
    }

    @Test
    fun `an ERP that never answers leaves the row pending and says so in the log`() = runTest {
        val recorder = Recorder()

        val outcome = dispatcher(recorder, entry = AeroWriteResult.Failed(null)).dispatch(settings, request)

        assertEquals(AeroDispatchOutcome.Unreachable, outcome)
        val log = recorder.logs.single()
        assertEquals("dispatch", log.event)
        assertNull("A transport failure has no status to report", log.httpStatus)
        assertTrue(log.note!!.startsWith("client=android · via=native"))
        assertTrue(log.note!!.contains("launch #42"))
    }

    @Test
    fun `an ERP that refuses keeps its own sentence`() = runTest {
        val recorder = Recorder()

        val outcome = dispatcher(recorder, entry = AeroWriteResult.Failed("produto inexistente")).dispatch(settings, request)

        assertEquals(AeroDispatchOutcome.Refused("produto inexistente"), outcome)
        assertEquals(200, recorder.logs.single().httpStatus)
    }

    @Test
    fun `an unconfigured integration sends nothing and logs nothing against the plant`() = runTest {
        val recorder = Recorder()

        val outcome = dispatcher(recorder).dispatch(AeroSettings(), request)

        assertEquals(AeroDispatchOutcome.Refused("not-configured"), outcome)
        assertEquals(emptyList<String>(), recorder.calls)
    }

    @Test
    fun `a sync reads the plant before storing the snapshot`() = runTest {
        val recorder = Recorder()
        val seat = AeroErpMovement(
            entLan = 900,
            productCode = "001192",
            productDesc = "MASSA DE PASTEL 500G",
            quantity = 15.0,
            entryDate = "2026-10-30",
            operatorName = "WILKIN",
            movementType = "E",
        )

        val outcome = dispatcher(
            recorder,
            movements = AeroMovementsResult.Ok(listOf(seat), skipped = 0),
            saved = AeroMovementsSaved(saved = 1, massas = 1, insumos = 0),
        ).syncDay(settings, "2026-10-30")

        assertEquals(AeroSyncOutcome.Synced(AeroMovementsSaved(saved = 1, massas = 1, insumos = 0)), outcome)
        assertEquals(
            listOf("erp:movements:2026-10-30", "mirror:save:2026-10-30:1", "mirror:log:sync:response"),
            recorder.calls,
        )
        assertTrue(recorder.logs.single().note!!.contains("saved=1"))
    }

    @Test
    fun `a failed read never stores an empty day`() = runTest {
        val recorder = Recorder()

        val outcome = dispatcher(recorder, movements = AeroMovementsResult.Failed(null))
            .syncDay(settings, "2026-10-30")

        assertEquals(AeroSyncOutcome.Unreachable, outcome)
        assertEquals(
            "a network blip must leave yesterday's snapshot standing",
            false,
            recorder.calls.any { it.startsWith("mirror:save") },
        )
    }

    @Test
    fun `a backend that refuses the snapshot is reported as a failure, not as a sync`() = runTest {
        val recorder = Recorder()

        val outcome = dispatcher(recorder, saved = null).syncDay(settings, "2026-10-30")

        assertEquals(AeroSyncOutcome.Failed("snapshot-refused"), outcome)
    }
}

/**
 * The note the plant's log will read.
 *
 * The prefix is how a line is attributed to this client, so the tests are about it surviving: the 256-character
 * column is trimmed from the end, never from the front.
 */
class AeroAuditTest {

    @Test
    fun `the prefix names the client and the transport`() {
        assertEquals("client=android · via=native", AeroAudit.PREFIX)
        assertEquals("client=android · via=native", AeroAudit.note(null))
        assertEquals("client=android · via=native", AeroAudit.note("   "))
    }

    @Test
    fun `a detail follows the prefix`() {
        assertEquals("client=android · via=native · launch #7", AeroAudit.note("launch #7"))
    }

    @Test
    fun `a note never exceeds the column, and the prefix is what survives`() {
        val note = AeroAudit.note("x".repeat(400))

        assertEquals(AeroAudit.NOTE_MAX, note.length)
        assertTrue(note.startsWith(AeroAudit.PREFIX))
        assertTrue(note.endsWith("x"))
    }

    @Test
    fun `the request carries the prefixed note`() {
        val request = AeroAudit.request(
            event = AeroAudit.Event.DISPATCH,
            destinationUrl = "http://192.168.25.250:1453/api/entradasitem",
            phase = "response",
            httpStatus = 200,
            durationMs = 1200,
            detail = "launch #7",
        )

        assertEquals("dispatch", request.event)
        assertEquals("response", request.phase)
        assertEquals(200, request.httpStatus)
        assertEquals("client=android · via=native · launch #7", request.note)
    }
}
