package com.trindade.app.aero

import com.trindade.app.BuildConfig
import com.trindade.app.network.AeroAuditLogRequest

/**
 * One integration attempt, as the shared log will read it.
 *
 * The web reports what it does through the same route, and the plant's log is read over SSH by a person
 * looking at several clients at once. `client=android · via=native` is the whole point of the prefix: it says
 * which of them produced the line without the reader having to guess from the shape of the event.
 *
 * The note is trimmed from the **end** when it does not fit: the prefix is the part that identifies the client,
 * and a log line that loses it is a line that cannot be attributed. It is never trimmed from the front.
 */
object AeroAudit {

    const val PREFIX = "client=android · via=native"

    /** The backend column holds 256 characters; the prefix and its separator take part of them. */
    const val NOTE_MAX = 256

    fun note(detail: String?): String {
        val text = detail?.trim().orEmpty()
        if (text.isEmpty()) return PREFIX
        val room = NOTE_MAX - PREFIX.length - SEPARATOR.length
        val tail = if (text.length <= room) text else text.take(room)
        return "$PREFIX$SEPARATOR$tail"
    }

    /**
     * The events this app reports.
     *
     * The same names the web uses, because the log is one table and a screen filtering by event must not have
     * to know which client wrote the line.
     */
    object Event {
        const val DISPATCH = "dispatch"
        const val SYNC = "sync"
        const val TEST = "test"
        const val IMPORT = "import"
    }

    fun request(
        event: String,
        destinationUrl: String,
        phase: String? = null,
        httpStatus: Int? = null,
        durationMs: Long? = null,
        detail: String? = null,
    ): AeroAuditLogRequest = AeroAuditLogRequest(
        event = event,
        destinationUrl = destinationUrl,
        phase = phase,
        httpStatus = httpStatus,
        durationMs = durationMs,
        note = note(detail),
    )

    private const val SEPARATOR = " · "
}

/**
 * Why an attempt ended, in the three answers the operator can act on.
 *
 * The distinction matters more than the message: `Unreachable` is the factory network and invites a retry
 * later, `Refused` is the ERP saying no to this payload and invites a correction.
 */
sealed interface AeroDispatchOutcome {
    data object Sent : AeroDispatchOutcome
    data object Unreachable : AeroDispatchOutcome
    data class Refused(val reason: String?) : AeroDispatchOutcome
}

/** The User-Agent this app sends, so the plant's log can attribute a request to the APK that made it. */
val AERO_USER_AGENT: String get() = "Trindade-Android/${BuildConfig.APP_VERSION_NAME}"
