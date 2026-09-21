package com.trindade.app.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Session tokens, encrypted with a key that never leaves the Android Keystore.
 *
 * The key is generated in the Keystore and cannot be exported, so the ciphertext in SharedPreferences
 * is useless to anything that reads the file: on a rooted device, or from a backup, the bytes are
 * only decryptable by code running as this app on this device.
 *
 * `androidx.security:security-crypto` would do the same thing in fewer lines, and was not used: the
 * artifact exists at 1.1.0 but the Jetpack Security library is in maintenance, and taking a
 * dependency that is no longer developed to avoid writing eighty lines of well-documented platform
 * code is a poor trade for the one thing in this app that touches credentials.
 *
 * Encryption is AES/GCM, which authenticates as well as encrypts, so a corrupted or tampered
 * ciphertext fails to decrypt rather than yielding plausible garbage.
 */
@Singleton
class KeystoreTokenStore @Inject constructor(
    @ApplicationContext context: Context,
) : TokenStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun accessToken(): String? = read(KEY_ACCESS_TOKEN)

    override fun refreshToken(): String? = read(KEY_REFRESH_TOKEN)

    override fun role(): String? = read(KEY_ROLE)

    override fun save(accessToken: String, refreshToken: String) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, encrypt(accessToken))
            .putString(KEY_REFRESH_TOKEN, encrypt(refreshToken))
            .apply()
    }

    override fun saveRole(role: String) {
        prefs.edit().putString(KEY_ROLE, encrypt(role)).apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private fun read(key: String): String? = prefs.getString(key, null)?.let { decrypt(it) }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // The IV is generated per encryption and stored beside the ciphertext, which is what GCM
        // requires: reusing one would let two ciphertexts be compared.
        return "${cipher.iv.base64()}:${ciphertext.base64()}"
    }

    private fun decrypt(stored: String): String? {
        val parts = stored.split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null

        // Null rather than a throw: a token that cannot be decrypted is a token that is not there,
        // and every caller already handles an absent session. ByteArray.toString would have produced
        // a plausible-looking string, which is the one outcome worth avoiding here.
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, parts[0].unbase64()))
            String(cipher.doFinal(parts[1].unbase64()), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication requirement: a background refresh has no one to prompt, and
                // the token is a bearer credential either way. Requiring it here would break the
                // silent refresh this whole slice exists to provide.
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.unbase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PREFS_NAME = "trindade.session"
        const val KEY_ACCESS_TOKEN = "access_token"
        const val KEY_REFRESH_TOKEN = "refresh_token"

        // The role is not a secret -- it is a word the app draws on screen -- and it is encrypted with the rest
        // because this file holds one session and one way of reading it is worth more than the round trip it
        // saves: a second, plaintext path into the same preferences is a second thing to remember when the file
        // is read or cleared.
        private const val KEY_ROLE = "role"

        const val KEY_ALIAS = "trindade.session.key"
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SEPARATOR = ":"
        const val GCM_TAG_BITS = 128
    }
}
