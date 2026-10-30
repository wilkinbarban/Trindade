package com.trindade.app.aero

import android.content.Context
import com.trindade.app.security.KeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The plant's address and this phone's ERP credentials, at rest on the device.
 *
 * One encrypted blob rather than four keys, because the four values are only meaningful together and a
 * half-written set is worse than none: a wrong address with yesterday's password would be retried instead
 * of corrected.
 *
 * Encryption is [KeystoreCipher], the same AES/GCM-with-a-Keystore-key arrangement the session store uses
 * and with its own alias. Nothing here is a secret when it is written: the ciphertext is what lands in
 * SharedPreferences, and the key that reads it cannot leave the device.
 */
/**
 * The integration's two device-owned facts, as the view model needs them.
 *
 * A port rather than the store itself, so a JVM test can drive the screen's decisions without a Context and so
 * the Android coupling stays in one class. [AeroSettingsStore] is the only implementation that ships.
 */
interface AeroSettingsPort {
    fun load(): AeroSettings
    fun save(settings: AeroSettings)
    fun isConfigured(): Boolean
    fun syncedAt(date: String): String?
    fun markSynced(date: String, at: String)
}

@Singleton
class AeroSettingsStore @Inject constructor(
    @ApplicationContext context: Context,
) : AeroSettingsPort {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val cipher = KeystoreCipher(KEY_ALIAS)

    /** The stored settings, or the defaults. Never throws: a blob this build cannot read is not a setting. */
    override fun load(): AeroSettings {
        val stored = prefs.getString(KEY_SETTINGS, null) ?: return AeroSettings()
        val plain = cipher.decrypt(stored) ?: return AeroSettings()
        return AeroSettingsCodec.decode(plain)
    }

    override fun save(settings: AeroSettings) {
        prefs.edit()
            .putString(KEY_SETTINGS, cipher.encrypt(AeroSettingsCodec.encode(settings)))
            .apply()
    }

    /**
     * Whether anything is on disk at all.
     *
     * The screen uses it to decide between "first run, here are the defaults" and "you already configured
     * this, here is what you typed", which is a different sentence even when the values coincide.
     */
    override fun isConfigured(): Boolean = prefs.contains(KEY_SETTINGS)

    fun clear() {
        prefs.edit().remove(KEY_SETTINGS).apply()
    }

    /**
     * When this device last asked the plant about a day.
     *
     * Stored per day and never derived from the snapshot's own timestamps: the operator asking "when did I
     * last sync?" means this phone, not when some other tablet happened to store a seat.
     */
    override fun syncedAt(date: String): String? = prefs.getString(syncKey(date), null)

    override fun markSynced(date: String, at: String) {
        prefs.edit().putString(syncKey(date), at).apply()
    }

    fun clearSyncedAt(date: String) {
        prefs.edit().remove(syncKey(date)).apply()
    }

    private fun syncKey(date: String) = "synced_at:$date"

    private companion object {
        const val PREFS_NAME = "trindade.aero"
        const val KEY_SETTINGS = "settings"

        /** Deliberately not the session store's alias: two secrets, two keys. */
        const val KEY_ALIAS = "trindade.aero.key"
    }
}
