package com.trindade.app.aero

import android.content.Context
import com.trindade.app.security.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The launches the operator has already said are on the physical tablet.
 *
 * A launch Trindade recorded but the ERP did not is a row with two honest answers: resend it, or confirm it
 * was written on the tablet by hand. The second answer leaves no trace in the ERP that this app can see, so
 * the operator's word is stored here -- per device, which is exactly the scope of the claim: this phone
 * said this launch is already on the tablet.
 *
 * Encrypted with the same instrument as the settings, not because an id is a secret but because this file
 * has one way of being read and one of being cleared. A second, plaintext path into the same preferences is
 * a second thing to remember.
 */
/** The acks this device owns: one per launch, remembered across restarts and never sent anywhere. */
interface TabletAckPort {
    fun ackedIds(): Set<Int>
    fun ack(launchId: Int)
}

@Singleton
class TabletAckStore @Inject constructor(
    @ApplicationContext context: Context,
) : TabletAckPort {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)
    private val json = Json { ignoreUnknownKeys = true }

    /** The launch ids this device has acknowledged, already deduplicated. */
    override fun ackedIds(): Set<Int> {
        val stored = prefs.getString(KEY_ACKS, null) ?: return emptySet()
        val plain = cipher.decrypt(stored) ?: return emptySet()
        val ids = runCatching { json.decodeFromString(StoredAcks.serializer(), plain) }.getOrNull()
            ?: return emptySet()
        return ids.launchIds.filter { it > 0 }.toSet()
    }

    /**
     * Acknowledges one launch.
     *
     * A singleton set rather than a growing list: the row has one state, and acknowledging it twice has to
     * be the same as acknowledging it once.
     */
    override fun ack(launchId: Int) {
        if (launchId <= 0) return
        write(ackedIds() + launchId)
    }

    fun clear() {
        prefs.edit().remove(KEY_ACKS).apply()
    }

    private fun write(ids: Set<Int>) {
        val payload = json.encodeToString(StoredAcks.serializer(), StoredAcks(ids.sorted()))
        prefs.edit().putString(KEY_ACKS, cipher.encrypt(payload)).apply()
    }

    @Serializable
    private data class StoredAcks(val launchIds: List<Int> = emptyList())

    private companion object {
        const val PREFS_NAME = "trindade.aero"
        const val KEY_ACKS = "tablet_acks"

        /** Its own key from the settings one: acknowledging a row must not require reading a password. */
        const val KEY_ALIAS = "trindade.aero.acks.key"
    }
}
