package com.trindade.app.aero

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The integration settings, as one value.
 *
 * Two of these four fields belong to the phone's owner and two belong to the plant: the address is a
 * property of the factory and only the owner account may move it, while the credentials are typed by
 * whoever is holding the device. That split is enforced by [aeroSettingsEditable], not by storing them in
 * different places.
 *
 * `password` is the only secret here. It lives encrypted by [AeroSettingsStore]; this class is the shape
 * the screen edits and the client reads, and it is deliberately not `toString`-safe.
 */
data class AeroSettings(
    val wsUrl: String = AERO_DEFAULT_WS_URL,
    /** The ERP login: the Delphi WebService authenticates per request with it. */
    val username: String = "",
    val password: String = "",
    /** `ENT_USU`: the numeric code the ERP knows this operator by; empty means [AERO_FALLBACK_USER_CODE]. */
    val userCode: String = "",
) {
    /** The address to actually call, normalized, or null when what is stored is unusable. */
    val normalizedWsUrl: String? get() = normalizeAeroWsUrl(wsUrl)

    val ready: Boolean get() = aeroSettingsReady(wsUrl, username, password)
}

/**
 * The stored form, in the names this app owns.
 *
 * A DTO rather than `@Serializable` on [AeroSettings], so the wire names of a preferences blob never
 * become the names the screen edits by accident. Unknown keys are ignored on read: a future field added by
 * a newer build must not make an older one lose every setting it does understand.
 */
@Serializable
private data class StoredAeroSettings(
    @SerialName("ws_url") val wsUrl: String = AERO_DEFAULT_WS_URL,
    @SerialName("username") val username: String = "",
    @SerialName("password") val password: String = "",
    @SerialName("user_code") val userCode: String = "",
)

/** The JSON codec for the stored settings, kept pure so the round trip is testable on a JVM. */
object AeroSettingsCodec {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(settings: AeroSettings): String = json.encodeToString(
        StoredAeroSettings.serializer(),
        StoredAeroSettings(
            wsUrl = settings.wsUrl,
            username = settings.username,
            password = settings.password,
            userCode = settings.userCode,
        ),
    )

    /**
     * Reads a stored blob, or the defaults.
     *
     * Corrupt is the same as absent on purpose: a settings file this build cannot read must not stop the
     * operator from opening the screen and typing the four values again, and it must not half-apply.
     */
    fun decode(raw: String?): AeroSettings {
        if (raw.isNullOrBlank()) return AeroSettings()
        val stored = runCatching { json.decodeFromString(StoredAeroSettings.serializer(), raw) }.getOrNull()
            ?: return AeroSettings()
        return AeroSettings(
            wsUrl = stored.wsUrl.ifBlank { AERO_DEFAULT_WS_URL },
            username = stored.username,
            password = stored.password,
            userCode = stored.userCode,
        )
    }
}
