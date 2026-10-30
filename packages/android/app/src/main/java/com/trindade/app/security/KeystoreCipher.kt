package com.trindade.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.KeyStore

/**
 * AES/GCM with a key that never leaves the Android Keystore.
 *
 * This is the same arrangement as [com.trindade.app.auth.KeystoreTokenStore] and it is deliberately a
 * second instrument rather than a shared one: that store owns the app's session, its key alias and its
 * preferences file, and the plant's credentials are a different secret with a different lifetime. A
 * refactor that migrated the session onto a new class would risk a silent logout on every installed
 * phone; a new alias and its own file cannot touch it.
 *
 * The key is generated in the Keystore and cannot be exported, so the ciphertext in SharedPreferences is
 * useless to anything that reads the file: on a rooted device, or from a backup -- and backups are off for
 * this app anyway -- the bytes are only decryptable by code running as this app on this device. GCM
 * authenticates as well as encrypts, so a corrupted or tampered value fails to decrypt rather than yielding
 * plausible garbage.
 *
 * `androidx.security:security-crypto` would do the same in fewer lines and was not used for the reason
 * recorded in `KeystoreTokenStore`: that artifact is in maintenance, and taking a dependency nobody is
 * developing to avoid writing platform code is a poor trade for the one thing in this app that touches
 * credentials.
 */
class KeystoreCipher(private val keyAlias: String) {

    fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        // The IV is generated per encryption and stored beside the ciphertext, which is what GCM requires:
        // reusing one would let two ciphertexts be compared.
        return "${cipher.iv.base64()}$SEPARATOR${ciphertext.base64()}"
    }

    /** Null rather than a throw: a value that cannot be decrypted is a value that is not there. */
    fun decrypt(stored: String): String? {
        val parts = stored.split(SEPARATOR, limit = 2)
        if (parts.size != 2) return null

        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                secretKey(),
                GCMParameterSpec(GCM_TAG_BITS, parts[0].unbase64()),
            )
            String(cipher.doFinal(parts[1].unbase64()), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // No user-authentication requirement: the plant credentials are read by a background
                // dispatch with nobody to prompt, and they are a bearer credential either way.
                .build(),
        )
        return generator.generateKey()
    }

    private fun ByteArray.base64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private fun String.unbase64(): ByteArray = Base64.decode(this, Base64.NO_WRAP)

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val SEPARATOR = ":"
        const val GCM_TAG_BITS = 128
    }
}
